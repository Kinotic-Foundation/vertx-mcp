package io.vertx.ext.mcp;

import io.vertx.ext.mcp.transport.VertxMcpTransport;
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
    private HttpServer httpServer;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        // Create HTTP server
        httpServer = vertx.createHttpServer();
        
        httpServer.requestHandler(transport.getRouter())
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
        if (transport != null) {
            transport.closeGracefully()
                    .doOnSuccess(v -> {
                        stopHttpServer(stopPromise);
                    })
                    .doOnError(throwable -> {
                        log.error("Error during graceful shutdown", throwable);
                        stopPromise.fail(throwable);
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
