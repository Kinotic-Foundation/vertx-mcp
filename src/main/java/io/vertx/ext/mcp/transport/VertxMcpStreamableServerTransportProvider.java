package io.vertx.ext.mcp.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of a Vert.x based Streamable HTTP transport provider for MCP.
 *
 * This implementation follows the MCP 2025-06-18 specification for Streamable HTTP transport,
 * which consolidates the previous HTTP+SSE transport into a single endpoint that supports
 * both POST and GET methods.
 *
 * Created By Navíd Mitchell 🤪on 8/11/25
 */
public class VertxMcpStreamableServerTransportProvider implements McpStreamableServerTransportProvider, VertxMcpTransport {

    private static final Logger log = LoggerFactory.getLogger(VertxMcpStreamableServerTransportProvider.class);

    public static final String MESSAGE_EVENT_TYPE = "message";

    private final ObjectMapper objectMapper;
    private final String mcpEndpoint;
    private final boolean disallowDelete;
    private final Vertx vertx;
    private final KeepAliveService keepAliveService;

    private McpStreamableServerSession.Factory sessionFactory;
    private final Map<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean isClosing = false;
    private Router router;

    @Builder
    public VertxMcpStreamableServerTransportProvider(ObjectMapper objectMapper,
                                                     String mcpEndpoint,
                                                     boolean disallowDelete,
                                                     Vertx vertx,
                                                     Duration keepAliveInterval) {
        // Validate required arguments
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper cannot be null");
        }
        if (vertx == null) {
            throw new IllegalArgumentException("vertx cannot be null");
        }
        
        this.objectMapper = objectMapper;
        this.mcpEndpoint = mcpEndpoint != null ? mcpEndpoint : "/mcp";
        this.disallowDelete = disallowDelete;
        this.vertx = vertx;
        this.keepAliveService = new KeepAliveService(vertx, keepAliveInterval);
    }

    private void initializeRouter() {
        router = Router.router(vertx);

        // Configure CORS
        router.route().handler(CorsHandler.create()
                                          .addOriginWithRegex(".*")
                                          .allowedHeaders(java.util.Set.of("Content-Type", "Authorization", "X-Requested-With",
                                                                           "Accept", "MCP-Session-Id", "MCP-Protocol-Version", "Last-Event-ID"))
                                          .allowedMethods(java.util.Set.of(HttpMethod.GET,
                                                                           HttpMethod.POST,
                                                                           HttpMethod.DELETE,
                                                                           HttpMethod.OPTIONS)));

        // Handle GET requests for SSE streams
        router.get(mcpEndpoint)
              .produces("text/event-stream")
              .handler(this::handleGet);

        // Handle POST requests for JSON-RPC messages
        router.post(mcpEndpoint)
              .consumes("application/json")
              .handler(BodyHandler.create())
              .handler(this::handlePost);

        // Handle DELETE requests for session termination
        router.delete(mcpEndpoint)
              .handler(this::handleDelete);

        // Health check endpoint
        router.get("/health").handler(ctx -> {
            ctx.response()
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject().put("status", "ok").encode());
        });

        // Start keep-alive service
        keepAliveService.start(v -> sendKeepAlivePing());
    }

    @Override
    public Router getRouter() {
        if (router == null) {
            initializeRouter();
        }
        return router;
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(io.modelcontextprotocol.spec.ProtocolVersions.MCP_2024_11_05,
                       io.modelcontextprotocol.spec.ProtocolVersions.MCP_2025_03_26);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            log.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        log.debug("Attempting to broadcast message to {} active sessions", sessions.size());

        return Flux.fromIterable(sessions.values())
                   .flatMap(session -> session.sendNotification(method, params)
                                              .doOnError(e -> log.error("Failed to send message to session {}: {}",
                                                                        session.getId(), e.getMessage()))
                                              .onErrorComplete())
                   .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            log.info("Initiating graceful shutdown with {} active sessions", sessions.size());
            isClosing = true;
            keepAliveService.stop();

            return Flux.fromIterable(sessions.values())
                       .flatMap(McpStreamableServerSession::closeGracefully)
                       .then()
                       .doOnSuccess(v -> {
                           log.debug("Graceful shutdown completed");
                           sessions.clear();
                       });
        });
    }

    /**
     * Send keep-alive ping to all active sessions.
     */
    private void sendKeepAlivePing() {
        if (sessions.isEmpty()) {
            return;
        }

        Flux.fromIterable(sessions.values())
            .flatMap(session -> {
                var typeRef = new TypeReference<>() {};
                return session.sendRequest(McpSchema.METHOD_PING, null, typeRef)
                              .doOnError(e -> log.warn("Failed to send keep-alive ping to session {}: {}",
                                                       session.getId(),
                                                       e.getMessage()))
                              .onErrorComplete();
            })
            .subscribe(
                    result -> {},
                    error -> log.error("Error during keep-alive ping", error)
            );
    }

    /**
     * Handles GET requests for establishing SSE streams.
     * This allows clients to listen for server messages without first sending data.
     */
    private void handleGet(RoutingContext ctx) {
        if (isClosing) {
            ctx.response()
               .setStatusCode(503)
               .putHeader("Content-Type", "text/plain")
               .end("Server is shutting down");
            return;
        }

        // Check if client accepts text/event-stream
        String acceptHeader = ctx.request().getHeader("Accept");
        if (acceptHeader == null || !acceptHeader.contains("text/event-stream")) {
            ctx.response()
               .setStatusCode(405)
               .putHeader("Content-Type", "text/plain")
               .end("Method Not Allowed - SSE stream not supported");
            return;
        }

        // Check for session ID
        String sessionId = ctx.request().getHeader("MCP-Session-Id");
        if (sessionId == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "text/plain")
               .end("Session ID required");
            return;
        }

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.response()
               .setStatusCode(404)
               .putHeader("Content-Type", "text/plain")
               .end("Session not found");
            return;
        }

        // Check for resumption with Last-Event-ID
        String lastEventId = ctx.request().getHeader("Last-Event-ID");
        if (lastEventId != null) {
            // Handle stream resumption
            handleStreamResumption(ctx, session, lastEventId);
            return;
        }

        // Set up SSE headers
        ctx.response()
           .putHeader("Content-Type", "text/event-stream")
           .putHeader("Cache-Control", "no-cache")
           .putHeader("Connection", "keep-alive")
           .setChunked(true);

        // Create listening stream
        VertxMcpStreamableSessionTransport sessionTransport = new VertxMcpStreamableSessionTransport(ctx.response());
        session.listeningStream(sessionTransport);
    }

    /**
     * Handles POST requests for JSON-RPC messages.
     * This is the main message exchange endpoint.
     */
    private void handlePost(RoutingContext ctx) {
        if (isClosing) {
            ctx.response()
               .setStatusCode(503)
               .putHeader("Content-Type", "text/plain")
               .end("Server is shutting down");
            return;
        }

        // Check Accept header for both application/json and text/event-stream
        String acceptHeader = ctx.request().getHeader("Accept");
        if (acceptHeader == null ||
                !(acceptHeader.contains("application/json") && acceptHeader.contains("text/event-stream"))) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "text/plain")
               .end("Accept header must include application/json and text/event-stream");
            return;
        }

        try {
            String body = ctx.body().asString();
            log.debug("Received POST message: {}", body);

            // Parse the JSON-RPC message
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

            // Handle different message types
            if (isInitializeRequest(message)) {
                handleInitializeRequest(ctx, message);
            } else {
                handleRegularMessage(ctx, message);
            }
        } catch (Exception e) {
            log.error("Failed to process POST message", e);
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject()
                            .put("error", "Invalid message format")
                            .encode());
        }
    }

    /**
     * Handles DELETE requests for session termination.
     */
    private void handleDelete(RoutingContext ctx) {
        if (isClosing) {
            ctx.response()
               .setStatusCode(503)
               .putHeader("Content-Type", "text/plain")
               .end("Server is shutting down");
            return;
        }

        if (disallowDelete) {
            ctx.response()
               .setStatusCode(405)
               .putHeader("Content-Type", "text/plain")
               .end("Method Not Allowed");
            return;
        }

        String sessionId = ctx.request().getHeader("MCP-Session-Id");
        if (sessionId == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "text/plain")
               .end("Session ID required");
            return;
        }

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.response()
               .setStatusCode(404)
               .putHeader("Content-Type", "text/plain")
               .end("Session not found");
            return;
        }

        // Terminate the session
        session.delete().subscribe(
                v -> {
                    sessions.remove(sessionId);
                    ctx.response()
                       .setStatusCode(200)
                       .putHeader("Content-Type", "text/plain")
                       .end("Session terminated");
                },
                error -> {
                    log.error("Error terminating session {}", sessionId, error);
                    ctx.response()
                       .setStatusCode(500)
                       .putHeader("Content-Type", "text/plain")
                       .end("Error terminating session");
                }
        );
    }

    /**
     * Handles initialization requests.
     */
    private void handleInitializeRequest(RoutingContext ctx, Object message) {
        try {
            if (sessionFactory == null) {
                ctx.response()
                   .setStatusCode(500)
                   .putHeader("Content-Type", "application/json")
                   .end(new JsonObject()
                                .put("error", "Session factory not configured")
                                .encode());
                return;
            }

            // Create a new session using the factory
            McpStreamableServerSession.McpStreamableServerSessionInit init = sessionFactory.startSession((McpSchema.InitializeRequest) message);
            McpStreamableServerSession session = init.session();

            sessions.put(session.getId(), session);

            // Process initialization
            init.initResult().subscribe(
                    initResult -> {
                        try {
                            // Return response with session ID header
                            ctx.response()
                               .putHeader("Content-Type", "application/json")
                               .putHeader("MCP-Session-Id", session.getId())
                               .end(objectMapper.writeValueAsString(initResult));
                        } catch (Exception e) {
                            log.error("Failed to serialize init result", e);
                            ctx.response()
                               .setStatusCode(500)
                               .putHeader("Content-Type", "application/json")
                               .end(new JsonObject()
                                            .put("error", "Failed to serialize response")
                                            .encode());
                        }
                    },
                    error -> {
                        log.error("Failed to get init result", error);
                        ctx.response()
                           .setStatusCode(500)
                           .putHeader("Content-Type", "application/json")
                           .end(new JsonObject()
                                        .put("error", "Initialization failed")
                                        .encode());
                    }
            );

        } catch (Exception e) {
            log.error("Failed to handle initialization", e);
            ctx.response()
               .setStatusCode(500)
               .putHeader("Content-Type", "application/json")
               .end(new JsonObject()
                            .put("error", "Initialization failed")
                            .encode());
        }
    }

    /**
     * Handles regular JSON-RPC messages (non-initialization).
     */
    private void handleRegularMessage(RoutingContext ctx, Object message) {
        String sessionId = ctx.request().getHeader("MCP-Session-Id");
        if (sessionId == null) {
            ctx.response()
               .setStatusCode(400)
               .putHeader("Content-Type", "text/plain")
               .end("Session ID required");
            return;
        }

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            ctx.response()
               .setStatusCode(404)
               .putHeader("Content-Type", "text/plain")
               .end("Session not found");
            return;
        }

        // Check if this is a request that needs a response stream
        if (isRequestMessage(message)) {
            // Return SSE stream for requests
            ctx.response()
               .putHeader("Content-Type", "text/event-stream")
               .putHeader("Cache-Control", "no-cache")
               .putHeader("Connection", "keep-alive");

            VertxMcpStreamableSessionTransport sessionTransport = new VertxMcpStreamableSessionTransport(ctx.response());
            session.responseStream((McpSchema.JSONRPCRequest) message, sessionTransport);
        } else {
            // Accept notifications and responses with 202 Accepted
            session.accept((McpSchema.JSONRPCNotification) message).subscribe(
                    v -> {
                        ctx.response()
                           .setStatusCode(202)
                           .end();
                    },
                    error -> {
                        log.error("Error accepting message", error);
                        ctx.response()
                           .setStatusCode(500)
                           .putHeader("Content-Type", "text/plain")
                           .end("Error processing message");
                    }
            );
        }
    }

    /**
     * Handles stream resumption with Last-Event-ID.
     */
    private void handleStreamResumption(RoutingContext ctx, McpStreamableServerSession session, String lastEventId) {
        ctx.response()
           .putHeader("Content-Type", "text/event-stream")
           .putHeader("Cache-Control", "no-cache")
           .putHeader("Connection", "keep-alive");

        ctx.response().setChunked(true);

        session.replay(lastEventId)
                .flatMap(event -> {
                    try {
                        String eventData = objectMapper.writeValueAsString(event);
                        return Mono.fromCompletionStage(sendSseEvent(ctx.response(), MESSAGE_EVENT_TYPE, eventData).toCompletionStage());
                    } catch (Exception e) {
                        log.error("Failed to serialize replayed event", e);
                        return Mono.error(e);
                    }
                })
                .doOnError(error -> {
                    log.error("Error during stream resumption", error);
                    if (!ctx.response().closed()) {
                        ctx.response().end();
                    }
                })
                .doOnComplete(() -> {
                    // Stream replay completed
                    if (!ctx.response().closed()) {
                        ctx.response().end();
                    }
                })
                .subscribe();
    }

    /**
     * Checks if a message is an initialization request.
     */
    private boolean isInitializeRequest(Object message) {
        try {
            if (message instanceof McpSchema.JSONRPCRequest request) {
                return McpSchema.METHOD_INITIALIZE.equals(request.method());
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a message is a request (has an ID).
     */
    private boolean isRequestMessage(Object message) {
        try {
            if (message instanceof McpSchema.JSONRPCRequest) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sends an SSE event to the client.
     */
    private Future<Void> sendSseEvent(HttpServerResponse response, String eventType, String data) {
        return response.write("event: " + eventType + "\ndata: " + data + "\n\n");
    }

    /**
     * Session transport implementation for the Streamable HTTP transport.
     */
    private class VertxMcpStreamableSessionTransport implements McpStreamableServerTransport {

        private final HttpServerResponse response;

        public VertxMcpStreamableSessionTransport(HttpServerResponse response) {
            this.response = response;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.fromSupplier(() -> {
                try {
                    return objectMapper.writeValueAsString(message);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).flatMap(jsonText -> {
                String eventData = "id: " + (messageId != null ? messageId : "") +
                        "\nevent: " + MESSAGE_EVENT_TYPE +
                        "\ndata: " + jsonText + "\n\n";
                return Mono.fromCompletionStage(response.write(eventData).toCompletionStage());
            }).doOnError(e -> {
                log.error("Failed to send message", e);
                if (!response.closed()) {
                    response.end();
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                if (!response.closed()) {
                    response.end();
                }
            });
        }

        @Override
        public void close() {
            if (!response.closed()) {
                response.end();
            }
        }
    }
}
