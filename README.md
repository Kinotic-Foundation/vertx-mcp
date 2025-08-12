# Vert.x MCP Server

A Vert.x-based transport implementation for the [Model Context Protocol (MCP) Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-server). This project provides a lightweight, non-blocking transport layer that integrates MCP servers with Vert.x applications.

## Overview

The Vert.x MCP Server provides:

- **Vert.x Transport**: A `VertxMcpTransport` interface that provides a Vert.x Router for integration into your Vert.x applications
- **SSE Transport**: Server-Sent Events (SSE) implementation for real-time bidirectional communication
- **Verticle Support**: A ready-to-use `McpVerticle` for easy deployment
- **Non-blocking**: Built on Vert.x for high-performance, event-driven architecture
- **Session Management**: Automatic client session handling with graceful shutdown support


## Quick Start

### 1. Create an MCP Server

First, create your MCP server using the official Java MCP SDK:

```java
import io.modelcontextprotocol.sdk.McpServer;
import io.modelcontextprotocol.sdk.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerCapabilities;

// Create a simple calculator tool
var calculatorTool = new McpServerFeatures.SyncToolSpecification(
    new McpSchema.Tool("calculator", "Basic calculator", """
        {
          "type": "object",
          "properties": {
            "operation": {"type": "string", "enum": ["add", "subtract", "multiply", "divide"]},
            "a": {"type": "number"},
            "b": {"type": "number"}
          },
          "required": ["operation", "a", "b"]
        }
        """),
    (exchange, arguments) -> {
        String operation = (String) arguments.get("operation");
        double a = ((Number) arguments.get("a")).doubleValue();
        double b = ((Number) arguments.get("b")).doubleValue();
        
        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> a / b;
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
        
        return new McpSchema.CallToolResult(result, false);
    }
);

// Build the MCP server
var mcpServer = McpServer.sync(transportProvider)
    .serverInfo("calculator-server", "1.0.0")
    .capabilities(ServerCapabilities.builder()
        .tools(true)
        .build())
    .tools(calculatorTool)
    .build();
```

### 2. Create the Vert.x Transport

Use the `VertxMcpSseServerTransport` to create a transport that provides a Vert.x Router:

```java
import io.vertx.ext.mcp.transport.VertxMcpSseServerTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

// Create the transport
var transport = VertxMcpSseServerTransport.builder()
    .baseUrl("http://localhost:8080")
    .messageEndpoint("/mcp/message")
    .sseEndpoint("/mcp/sse")
    .keepAliveInterval(Duration.ofSeconds(30))
    .objectMapper(new ObjectMapper())
    .vertx(vertx)
    .build();

```

### 3. Integrate with Your Vert.x Application

#### Option A: Use the Router directly

```java
import io.vertx.ext.web.Router;

// Get the router from the transport
Router mcpRouter = transport.getRouter();

// Mount it in your main application router
Router mainRouter = Router.router(vertx);
mainRouter.mountSubRouter("/mcp", mcpRouter);

// Create HTTP server
vertx.createHttpServer()
    .requestHandler(mainRouter)
    .listen(8080, ar -> {
        if (ar.succeeded()) {
            System.out.println("Server started on port 8080");
        } else {
            System.err.println("Failed to start server: " + ar.cause());
        }
    });
```

#### Option B: Use the provided Verticle

```java
import io.vertx.ext.mcp.McpVerticle;

// Deploy the MCP verticle
vertx.deployVerticle(new McpVerticle(8080, transport), ar -> {
    if (ar.succeeded()) {
        System.out.println("MCP Verticle deployed successfully");
    } else {
        System.err.println("Failed to deploy MCP Verticle: " + ar.cause());
    }
});
```

### 4. Complete Example

Here's a complete working example:

```java
import io.vertx.core.Vertx;
import io.vertx.ext.mcp.McpVerticle;
import io.vertx.ext.mcp.transport.VertxMcpSseServerTransport;
import io.modelcontextprotocol.sdk.McpServer;
import io.modelcontextprotocol.sdk.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;

public class McpServerExample {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        
        // Create MCP server with a simple tool
        var calculatorTool = new McpServerFeatures.SyncToolSpecification(
            new McpSchema.Tool("calculator", "Basic calculator", """
                {
                  "type": "object",
                  "properties": {
                    "operation": {"type": "string", "enum": ["add", "subtract", "multiply", "divide"]},
                    "a": {"type": "number"},
                    "b": {"type": "number"}
                  },
                  "required": ["operation", "a", "b"]
                }
                """),
            (exchange, arguments) -> {
                String operation = (String) arguments.get("operation");
                double a = ((Number) arguments.get("a")).doubleValue();
                double b = ((Number) arguments.get("b")).doubleValue();
                
                double result = switch (operation) {
                    case "add" -> a + b;
                    case "subtract" -> a - b;
                    case "multiply" -> a * b;
                    case "divide" -> a / b;
                    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
                };
                
                return new McpSchema.CallToolResult(result, false);
            }
        );

        // Create transport
        var transport = VertxMcpSseServerTransport.builder()
            .baseUrl("http://localhost:8080")
            .messageEndpoint("/mcp/message")
            .sseEndpoint("/mcp/sse")
            .keepAliveInterval(Duration.ofSeconds(30))
            .objectMapper(new ObjectMapper())
            .vertx(vertx)
            .build();

        // Create MCP server
        var mcpServer = McpServer.sync(transport)
            .serverInfo("calculator-server", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(calculatorTool)
            .build();

        // Deploy the MCP verticle
        vertx.deployVerticle(new McpVerticle(8080, transport), ar -> {
            if (ar.succeeded()) {
                System.out.println("MCP Server started on port 8080");
                System.out.println("SSE endpoint: http://localhost:8080/mcp/sse");
                System.out.println("Message endpoint: http://localhost:8080/mcp/message");
            } else {
                System.err.println("Failed to start MCP Server: " + ar.cause());
            }
        });
    }
}
```

## Configuration Options

The `VertxMcpSseServerTransport` supports several configuration options:

- **`baseUrl`**: Base URL for your server (required)
- **`messageEndpoint`**: Endpoint for receiving JSON-RPC messages (default: `/message`)
- **`sseEndpoint`**: Endpoint for SSE connections (default: `/sse`)
- **`keepAliveInterval`**: Interval for keep-alive ping messages (default: 30 seconds)
- **`objectMapper`**: Jackson ObjectMapper for JSON serialization
- **`vertx`**: Vert.x instance

## Architecture

The transport implements the MCP protocol using:

1. **SSE Connection** (`/sse`): Establishes Server-Sent Events connection for server-to-client communication
2. **Message Endpoint** (`/message`): Receives JSON-RPC messages from clients
3. **Session Management**: Automatically manages client sessions and cleanup
4. **Keep-alive**: Sends periodic ping messages to prevent connection timeouts

## Testing

You can test your MCP server using any MCP client. The server exposes:

- **SSE endpoint**: `http://localhost:8080/mcp/sse` for establishing connections
- **Message endpoint**: `http://localhost:8080/mcp/message?sessionId=<id>` for sending requests

## Graceful Shutdown

The transport supports graceful shutdown:

```java
// Close the transport gracefully
transport.closeGracefully()
    .doOnSuccess(v -> System.out.println("Transport closed successfully"))
    .doOnError(e -> System.err.println("Error closing transport: " + e))
    .subscribe();
```

## Roadmap

The following features are planned for future releases:

### Streamable HTTP Transport
Support for the new [Streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http) specification, which replaces the current HTTP+SSE transport. This will provide:
- Single MCP endpoint supporting both POST and GET methods
- Improved session management with session IDs
- Better resumability and redelivery support
- Enhanced security with Origin header validation

### Authorization Support
Implementation of [MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization) capabilities including:
- OAuth 2.1 compliant authorization flow
- Dynamic client registration
- Access token validation and audience binding
- Resource parameter support for secure token issuance

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

## License

This project is licensed under the Apache License, Version 2.0.
