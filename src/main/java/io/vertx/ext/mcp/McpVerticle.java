package io.vertx.ext.mcp;

import io.vertx.ext.mcp.transport.VertxMcpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpAsyncServer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;

/**
 * Verticle that sets up the MCP server with Vert.x transport
 * 
 * Created By Navíd Mitchell 🤪on 8/10/25
 */
@RequiredArgsConstructor
public class McpVerticle extends AbstractVerticle {
    
    private static final Logger log = LoggerFactory.getLogger(McpVerticle.class);
    
    private final int mcpPort;
    private final VertxMcpTransport transport;
    private final McpServer.AsyncSpecification<?> mcpServerSpec;
    private McpAsyncServer mcpServer;
    private HttpServer httpServer;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        // Build the MCP server from the specification
        mcpServer = mcpServerSpec.build();
        
        // Get the router from the transport (the MCP server manages the transport lifecycle)
        var router = transport.getRouter();
        
        // Create HTTP server
        httpServer = vertx.createHttpServer();
        
        httpServer.requestHandler(router)
                .listen(mcpPort, ar -> {
                    if (ar.succeeded()) {
                        log.info("MCP Transport server started on port {}", mcpPort);
                        startPromise.complete();
                    } else {
                        log.error("Failed to start MCP Transport server", ar.cause());
                        startPromise.fail(ar.cause());
                    }
                });
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        // Close the MCP server gracefully first (this manages the transport lifecycle)
        if (mcpServer != null) {
            mcpServer.closeGracefully()
                    .doOnSuccess(v -> {
                        // MCP server has closed, now close the HTTP server
                        stopHttpServer(stopPromise);
                    })
                    .doOnError(throwable -> {
                        log.error("Error during MCP server graceful shutdown", throwable);
                        // Continue with HTTP server shutdown even if MCP server fails
                        stopHttpServer(stopPromise);
                    })
                    .subscribe();
        } else {
            stopHttpServer(stopPromise);
        }
    }

    private void stopHttpServer(Promise<Void> stopPromise) {
        if (httpServer != null) {
            httpServer.close(ar -> {
                if (ar.succeeded()) {
                    log.info("MCP Transport server stopped");
                    stopPromise.complete();
                } else {
                    log.error("Failed to stop MCP Transport server", ar.cause());
                    stopPromise.fail(ar.cause());
                }
            });
        } else {
            stopPromise.complete();
        }
    }
}
