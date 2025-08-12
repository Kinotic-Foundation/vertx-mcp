package io.vertx.ext.mcp.transport;

import io.vertx.ext.web.Router;
import reactor.core.publisher.Mono;

/**
 * MCP Transport for Vert.x.
 * 
 * This interface provides a way to create a MCP transport for Vert.x.
 * 
 * Created By Navíd Mitchell 🤪on 8/11/25
 */
public interface VertxMcpTransport {

    /**
     * Get the router for the Vert.x MCP transport.
     * 
     * @return The router for the Vert.x MCP transport.
     */
    Router getRouter();

    /** 
     * Close the Vert.x MCP transport gracefully.
     * 
     * @return A Mono that completes when the transport is closed.
     */
    Mono<Void> closeGracefully();
}
