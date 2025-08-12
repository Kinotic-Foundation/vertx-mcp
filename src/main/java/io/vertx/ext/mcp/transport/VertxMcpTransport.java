package io.vertx.ext.mcp.transport;

import io.vertx.ext.web.Router;
import reactor.core.publisher.Mono;

/**
 * Created By Navíd Mitchell 🤪on 8/11/25
 */
public interface VertxMcpTransport {
    Router getRouter();

    Mono<Void> closeGracefully();
}
