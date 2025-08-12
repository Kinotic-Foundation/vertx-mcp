package io.vertx.ext.mcp.transport;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Service for managing keep-alive functionality in MCP transports.
 *
 * This service can be composed into transport implementations to provide
 * periodic ping functionality without inheritance.
 *
 * Created By Navíd Mitchell 🤪on 8/12/25
 */
public class KeepAliveService {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveService.class);

    private final Vertx vertx;
    private final Duration keepAliveInterval;
    private Long keepAliveTimerId;
    private volatile boolean isActive = true;

    public KeepAliveService(Vertx vertx, Duration keepAliveInterval) {
        this.vertx = vertx;
        this.keepAliveInterval = keepAliveInterval != null ? keepAliveInterval : Duration.ofSeconds(30);
    }

    /**
     * Start the keep-alive ping mechanism.
     *
     * @param pingAction Action to perform for each ping (e.g., send ping to sessions)
     */
    public void start(Consumer<Void> pingAction) {
        if (keepAliveInterval.isZero() || keepAliveInterval.isNegative()) {
            return;
        }

        long intervalMs = keepAliveInterval.toMillis();
        keepAliveTimerId = vertx.setPeriodic(intervalMs, timerId -> {
            if (isActive && pingAction != null) {
                log.trace("Sending keep-alive ping");
                pingAction.accept(null);
            }
        });
        log.debug("Started keep-alive ping mechanism with interval: {}", keepAliveInterval);
    }

    /**
     * Stop the keep-alive ping mechanism.
     */
    public void stop() {
        if (keepAliveTimerId != null) {
            vertx.cancelTimer(keepAliveTimerId);
            keepAliveTimerId = null;
            isActive = false;
            log.debug("Stopped keep-alive ping mechanism");
        }
    }

    /**
     * Check if the keep-alive service is currently active.
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Get the configured keep-alive interval.
     */
    public Duration getKeepAliveInterval() {
        return keepAliveInterval;
    }
}
