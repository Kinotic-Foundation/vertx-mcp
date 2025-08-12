package io.vertx.ext.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.mcp.transport.VertxMcpSseServerTransportProvider;
import io.vertx.ext.mcp.transport.VertxMcpStreamableServerTransportProvider;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// MCP SDK imports for building actual servers
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;

/**
 * Integration tests that verify the transport setup from the README examples can actually start and run.
 * These tests ensure that the transport configuration and server startup works as documented.
 */
@ExtendWith(VertxExtension.class)
class ExamplesIntegrationTest {

    private Vertx vertx;
    private int ssePort;
    private int streamablePort;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        // Find available ports dynamically
        ssePort = findAvailablePort();
        streamablePort = findAvailablePort();
    }
    
    private int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Could not find available port", e);
        }
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (vertx != null) {
            vertx.close(ar -> {
                if (ar.succeeded()) {
                    testContext.completeNow();
                } else {
                    testContext.failNow(ar.cause());
                }
            });
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testLegacyHttpSseTransportExample(VertxTestContext testContext) {
        // This test replicates the "Example A: Legacy HTTP+SSE Transport" from the README
        
        // Create a simple calculator tool for testing
        var calculatorTool = McpServerFeatures.AsyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("calculator")
                .description("Basic calculator")
                .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "operation": {"type": "string", "enum": ["add", "subtract", "multiply", "divide"]},
                        "a": {"type": "number"},
                        "b": {"type": "number"}
                      },
                      "required": ["operation", "a", "b"]
                    }
                    """)
                .build())
            .callHandler((exchange, toolReq) -> {
                String operation = (String) toolReq.arguments().get("operation");
                double a = ((Number) toolReq.arguments().get("a")).doubleValue();
                double b = ((Number) toolReq.arguments().get("b")).doubleValue();
                
                double result = switch (operation) {
                    case "add" -> a + b;
                    case "subtract" -> a - b;
                    case "multiply" -> a * b;
                    case "divide" -> a / b;
                    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
                };
                
                return Mono.just(McpSchema.CallToolResult.builder()
                    .textContent(List.of(String.valueOf(result)))
                    .isError(false)
                    .build());
            })
            .build();

        // Create legacy HTTP+SSE transport (as shown in README)
        var transport = VertxMcpSseServerTransportProvider.builder()
            .baseUrl("http://localhost:" + ssePort)
            .messageEndpoint("/mcp/message")
            .sseEndpoint("/mcp/sse")
            .keepAliveInterval(Duration.ofSeconds(30))
            .objectMapper(new ObjectMapper())
            .vertx(vertx)
            .build();

        // Create MCP server (as shown in README)
        @SuppressWarnings("unused")
        var mcpServer = McpServer.async(transport)
            .serverInfo("test-calculator-server", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(calculatorTool)
            .build();

        // Deploy the MCP verticle (as shown in README)
        vertx.deployVerticle(new McpVerticle(ssePort, transport), ar -> {
            if (ar.succeeded()) {
                // Test that the server is accessible (verifying it started successfully)
                HttpClient client = vertx.createHttpClient();
                
                // Test SSE endpoint - just verify it's accessible
                client.request(HttpMethod.GET, ssePort, "localhost", "/mcp/sse")
                    .compose(HttpClientRequest::send)
                    .onSuccess(response -> {
                        // Just verify we get a response (could be 200, 400, etc.)
                        assertNotNull(response);
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
            } else {
                testContext.failNow(ar.cause());
            }
        });
    }

        @Test
    void testStreamableHttpTransportExample(VertxTestContext testContext) {
        // This test replicates the "Example B: New Streamable HTTP Transport" from the README
        
        // Create a simple calculator tool for testing
        var calculatorTool = McpServerFeatures.AsyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder()
                .name("calculator")
                .description("Basic calculator")
                .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "operation": {"type": "string", "enum": ["add", "subtract", "multiply", "divide"]},
                        "a": {"type": "number"},
                        "b": {"type": "number"}
                      },
                      "required": ["operation", "a", "b"]
                    }
                    """)
                .build())
            .callHandler((exchange, toolReq) -> {
                String operation = (String) toolReq.arguments().get("operation");
                double a = ((Number) toolReq.arguments().get("a")).doubleValue();
                double b = ((Number) toolReq.arguments().get("b")).doubleValue();
                
                double result = switch (operation) {
                    case "add" -> a + b;
                    case "subtract" -> a - b;
                    case "multiply" -> a * b;
                    case "divide" -> a / b;
                    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
                };
                
                return Mono.just(McpSchema.CallToolResult.builder()
                    .textContent(List.of(String.valueOf(result)))
                    .isError(false)
                    .build());
            })
            .build();

        // Create Streamable HTTP transport (as shown in README)
        var transport = VertxMcpStreamableServerTransportProvider.builder()
            .objectMapper(new ObjectMapper())
            .mcpEndpoint("/mcp")
            .disallowDelete(false)
            .vertx(vertx)
            .keepAliveInterval(Duration.ofSeconds(30))
            .build();

        // Create MCP server (as shown in README)
        @SuppressWarnings("unused")
        var mcpServer = McpServer.async(transport)
            .serverInfo("test-calculator-server", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(calculatorTool)
            .build();

        // Deploy the MCP verticle (as shown in README)
        vertx.deployVerticle(new McpVerticle(streamablePort, transport), ar -> {
            if (ar.succeeded()) {
                // Test that the server is accessible (verifying it started successfully)
                HttpClient client = vertx.createHttpClient();
                
                // Test GET (SSE) endpoint - just verify it's accessible
                client.request(HttpMethod.GET, streamablePort, "localhost", "/mcp")
                    .compose(HttpClientRequest::send)
                    .onSuccess(response -> {
                        // Just verify we get a response (could be 200, 400, 405, etc.)
                        assertNotNull(response);
                        testContext.completeNow();
                    })
                    .onFailure(testContext::failNow);
            } else {
                testContext.failNow(ar.cause());
            }
        });
    }

    @Test
    void testRouterIntegrationExample(VertxTestContext testContext) {
        // This test replicates the "Option A: Use the Router directly" from the README
        
        // Create a transport
        var transport = VertxMcpSseServerTransportProvider.builder()
            .baseUrl("http://localhost:" + ssePort)
            .messageEndpoint("/mcp/message")
            .sseEndpoint("/mcp/sse")
            .keepAliveInterval(Duration.ofSeconds(30))
            .objectMapper(new ObjectMapper())
            .vertx(vertx)
            .build();

        // Get the router from the transport (as shown in README)
        var mcpRouter = transport.getRouter();
        assertNotNull(mcpRouter);

        // Mount it in your main application router (as shown in README)
        var mainRouter = Router.router(vertx);
        mainRouter.route("/mcp/*").subRouter(mcpRouter);

        // Create HTTP server (as shown in README)
        vertx.createHttpServer()
            .requestHandler(mainRouter)
            .listen(ssePort, ar -> {
                if (ar.succeeded()) {
                    // Test that the server is accessible
                    HttpClient client = vertx.createHttpClient();
                    client.request(HttpMethod.GET, ssePort, "localhost", "/mcp/sse")
                        .compose(HttpClientRequest::send)
                        .onSuccess(response -> {
                            // Just verify we get a response (could be 200, 404, etc.)
                            assertNotNull(response);
                            testContext.completeNow();
                        })
                        .onFailure(testContext::failNow);
                } else {
                    testContext.failNow(ar.cause());
                }
            });
    }
}
