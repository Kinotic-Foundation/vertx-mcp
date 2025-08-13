package io.vertx.ext.mcp.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.Builder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Server-side implementation of the MCP (Model Context Protocol) HTTP transport using
 * Vert.x and Server-Sent Events (SSE). This implementation provides a bidirectional communication
 * channel between MCP clients and servers using HTTP POST for client-to-server messages
 * and SSE for server-to-client messages.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Implements the {@link McpServerTransportProvider} interface that allows managing
 * {@link McpServerSession} instances and enabling their communication with the
 * {@link McpServerTransport} abstraction.</li>
 * <li>Uses Vert.x for non-blocking request handling and SSE support</li>
 * <li>Maintains client sessions for reliable message delivery</li>
 * <li>Supports graceful shutdown with session cleanup</li>
 * <li>Thread-safe message broadcasting to multiple clients</li>
 * </ul>
 *
 * <p>
 * The transport sets up two main endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - For establishing SSE connections with clients</li>
 * <li>Message endpoint (configurable) - For receiving JSON-RPC messages from clients</li>
 * </ul>
 *
 * <p>
 * This implementation is thread-safe and can handle multiple concurrent client
 * connections. It uses {@link ConcurrentHashMap} for session management and Vert.x's
 * non-blocking APIs for message processing and delivery.
 *
 * Created By Navíd Mitchell 🤪on 8/10/25
 */
public class VertxMcpSseServerTransportProvider implements McpServerTransportProvider, VertxMcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(VertxMcpSseServerTransportProvider.class);

    /**
     * Event type for JSON-RPC messages sent through the SSE connection.
     */
    public static final String MESSAGE_EVENT_TYPE = "message";

    /**
     * Event type for sending the message endpoint URI to clients.
     */
    public static final String ENDPOINT_EVENT_TYPE = "endpoint";

    /**
     * Default SSE endpoint path as specified by the MCP transport specification.
     */
    public static final String DEFAULT_SSE_ENDPOINT = "/sse";

    /**
     * Default message endpoint path as specified by the MCP transport specification.
     */
    public static final String DEFAULT_MESSAGE_ENDPOINT = "/message";

    /**
     * Default keep alive interval as specified by the MCP transport specification.
     */
    public static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(30);

    /**
     * Base URL for the message endpoint. This is used to construct the full URL for clients to send their JSON-RPC messages.
     */
    private final String baseUrl;
    private final String messageEndpoint;
    private final String sseEndpoint;
    private final Duration keepAliveInterval;
    private final Vertx vertx;
    private final ObjectMapper objectMapper;

    // Session management - stores active client sessions
    private McpServerSession.Factory sessionFactory;
    private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();

    // Shutdown state management
    private volatile boolean isClosing = false;
    private Router router;

    // Keep-alive service for periodic ping messages
    private final KeepAliveService keepAliveService;

    @Builder
    public VertxMcpSseServerTransportProvider(String baseUrl,
                                              String messageEndpoint,
                                              String sseEndpoint,
                                              Duration keepAliveInterval,
                                              ObjectMapper objectMapper,
                                              Vertx vertx) {
        // Validate required arguments
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl cannot be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper cannot be null");
        }
        if (vertx == null) {
            throw new IllegalArgumentException("vertx cannot be null");
        }
        
        this.baseUrl = baseUrl;
        this.messageEndpoint = messageEndpoint != null ? messageEndpoint : DEFAULT_MESSAGE_ENDPOINT;
        this.sseEndpoint = sseEndpoint != null ? sseEndpoint : DEFAULT_SSE_ENDPOINT;
        this.keepAliveInterval = keepAliveInterval != null ? keepAliveInterval : DEFAULT_KEEP_ALIVE_INTERVAL;
        this.objectMapper = objectMapper;
        this.vertx = vertx;
        this.keepAliveService = new KeepAliveService(vertx, this.keepAliveInterval);
    }

    private void initializeRouter() {
        router = Router.router(vertx);

        router.route().handler(BodyHandler.create(false));

        router.get(sseEndpoint).handler(this::handleSseConnection);

        router.post(messageEndpoint).handler(this::handleMessage);

        startKeepAlive();
    }

    /**
     * Starts the keep-alive ping mechanism using the KeepAliveService.
     * Sends periodic ping messages to all active sessions to prevent idle timeouts.
     */
    private void startKeepAlive() {
        keepAliveService.start(v -> sendKeepAlivePing());
    }

    /**
     * Send keep-alive ping to all active sessions.
     */
    private void sendKeepAlivePing() {
        if (!isClosing && !sessions.isEmpty()) {
            logger.trace("Sending keep-alive ping to {} active sessions", sessions.size());
            var typeRef = new TypeReference<>() {};
            Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendRequest(McpSchema.METHOD_PING, null, typeRef)
                                           .doOnError(e -> logger.warn("Failed to send keep-alive ping to session {}: {}",
                                                                       session.getId(), e.getMessage()))
                                           .onErrorComplete()) // Continue with other sessions even if one fails
                .subscribe(
                        result -> {},
                        error -> logger.error("Error during keep-alive ping", error)
                );
        }
    }

    /**
     * Stops the keep-alive ping mechanism.
     */
    private void stopKeepAlive() {
        keepAliveService.stop();
    }

    /**
     * Returns the Router that can be mounted into a Vert.x application.
     * This router contains all the MCP transport endpoints.
     *
     * @return The configured Router for handling MCP HTTP requests
     */
    @Override
    public Router getRouter() {
        if (router == null) {
            initializeRouter();
        }
        return router;
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2024_11_05);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

        return Flux.fromIterable(sessions.values())
                   .flatMap(session -> session.sendNotification(method, params)
                                              .doOnError(e -> logger.error("Failed to send message to session {}: {}",
                                                                           session.getId(), e.getMessage()))
                                              .onErrorComplete())
                   .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        isClosing = true;
        stopKeepAlive(); // Stop keep-alive when shutting down
        return Flux.fromIterable(sessions.values())
                   .doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size()))
                   .flatMap(McpServerSession::closeGracefully)
                   .then()
                   .doOnSuccess(v -> {
                       logger.debug("Graceful shutdown completed");
                       sessions.clear();
                   });
    }

    private void handleSseConnection(RoutingContext context) {
        if (isClosing) {
            context.response()
                   .setStatusCode(503)
                   .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
                   .end("Server is shutting down");
            return;
        }

        HttpServerResponse response = context.response();

        response.putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
                .putHeader(HttpHeaders.CONNECTION, "keep-alive")
                .setChunked(true);

        VertxMcpSessionTransport sessionTransport = new VertxMcpSessionTransport(response);
        McpServerSession session = sessionFactory.create(sessionTransport);
        String sessionId = session.getId();

        logger.debug("Created new SSE connection for session: {}", sessionId);
        sessions.put(sessionId, session);

        logger.debug("Sending initial endpoint event to session: {}", sessionId);
        sendSseEvent(response, ENDPOINT_EVENT_TYPE, baseUrl + messageEndpoint + "?sessionId=" + sessionId);

        response.closeHandler(v -> {
            logger.debug("Session {} closed", sessionId);
            sessions.remove(sessionId);
        });
    }

    private void handleMessage(RoutingContext context) {
        if (isClosing) {
            context.response()
                   .setStatusCode(503)
                   .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
                   .end("Server is shutting down");
            return;
        }

        String sessionId = context.queryParams().get("sessionId");
        if (sessionId == null) {
            context.response()
                   .setStatusCode(400)
                   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                   .end(new JsonObject()
                                .put("error", "Session ID missing in message endpoint")
                                .encode());
            return;
        }

        McpServerSession session = sessions.get(sessionId);
        if (session == null) {
            context.response()
                   .setStatusCode(404)
                   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                   .end(new JsonObject()
                                .put("error", "Session not found: " + sessionId)
                                .encode());
            return;
        }

        String body = context.body().asString();
        try {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

            // Process message directly through the session, like the Spring implementation
            session.handle(message)
                   .doOnSuccess(response -> context.response()
                                                   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                                                   .end())
                   .doOnError(error -> {
                       logger.error("Error processing message: {}", error.getMessage());
                       context.response()
                              .setStatusCode(500)
                              .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                              .end(new JsonObject()
                                           .put("error", error.getMessage())
                                           .encode());
                   })
                   .subscribe();

        } catch (IllegalArgumentException | IOException e) {
            logger.error("Failed to deserialize message: {}", e.getMessage());
            context.response()
                   .setStatusCode(400)
                   .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                   .end(new JsonObject()
                                .put("error", "Invalid message format")
                                .encode());
        }
    }

    private Future<Void> sendSseEvent(HttpServerResponse response, String eventType, String data) {
        return response.write("event: "+eventType+"\ndata: "+data+"\n\n");
    }

    private class VertxMcpSessionTransport implements McpServerTransport {

        private final HttpServerResponse response;

        public VertxMcpSessionTransport(HttpServerResponse response) {
            this.response = response;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromSupplier(() -> {
                try {
                    return objectMapper.writeValueAsString(message);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).flatMap(jsonText -> {
                return Mono.fromCompletionStage(sendSseEvent(response, MESSAGE_EVENT_TYPE, jsonText).toCompletionStage());
            }).doOnError(e -> {
                logger.error("Failed to send message to session", e);
                response.end();
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
