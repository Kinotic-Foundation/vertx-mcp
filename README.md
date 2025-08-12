# Vert.x MCP Server

A Vert.x-based transport implementation for the [Model Context Protocol (MCP) Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-server). This project provides a lightweight, non-blocking transport layer that integrates MCP servers with Vert.x applications.

## Overview

The Vert.x MCP Server provides:

- **Vert.x Transport**: A `VertxMcpTransport` interface that provides a Vert.x Router for integration into your Vert.x applications
- **SSE Transport**: Server-Sent Events (SSE) implementation for real-time bidirectional communication
- **Streamable HTTP Transport**: New MCP 2025-06-18 Streamable HTTP transport implementation
- **Verticle Support**: A ready-to-use `McpVerticle` for easy deployment
- **Non-blocking**: Built on Vert.x for high-performance, event-driven architecture
- **Session Management**: Automatic client session handling with graceful shutdown support

## ⚠️ Experimental Status

**This project is currently in experimental status and has not been fully tested in production environments.**

- The implementation follows the MCP specification but may contain bugs or incomplete features
- API changes are possible as the project matures
- Performance characteristics have not been thoroughly benchmarked
- Please report any bugs, issues, or unexpected behavior you encounter
- Contributions and feedback are welcome to help improve the project

## Installation

### Maven
```xml
<dependency>
    <groupId>org.kinotic</groupId>
    <artifactId>vertx-mcp</artifactId>
    <version>4.5.0</version>
</dependency>
```

### Gradle
```gradle
dependencies {
    implementation 'org.kinotic:vertx-mcp:4.5.0'
}
```

### Version Compatibility

- **4.5.x versions**: Compatible with Vert.x 4.5.x
- **5.0.x versions**: Will be compatible with Vert.x 5.x (not yet supported)

## Quick Start

### 1. Create an MCP Server

First, create your MCP server using the official Java MCP SDK:

```java
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerCapabilities;
import java.util.List;
import reactor.core.publisher.Mono;

// Create a simple calculator tool
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

// Create the MCP server specification (don't call .build() yet)
var mcpServerSpec = McpServer.async(transportProvider)
    .serverInfo("calculator-server", "1.0.0")
    .capabilities(ServerCapabilities.builder()
        .tools(true)
        .build())
    .tools(calculatorTool);
```

### 2. Create the Vert.x Transport

#### Option A: Use the Legacy HTTP+SSE Transport

```java
import io.vertx.ext.mcp.transport.VertxMcpSseServerTransportProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

// Create the transport
var transport = VertxMcpSseServerTransportProvider.builder()
    .baseUrl("http://localhost:8080")
    .messageEndpoint("/mcp/message")
    .sseEndpoint("/mcp/sse")
    .keepAliveInterval(Duration.ofSeconds(30))
    .objectMapper(new ObjectMapper())
    .vertx(vertx)
    .build();
```

#### Option B: Use the New Streamable HTTP Transport

```java
import io.vertx.ext.mcp.transport.VertxMcpStreamableServerTransportProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

// Create the Streamable HTTP transport
var transport = VertxMcpStreamableServerTransportProvider.builder()
    .objectMapper(new ObjectMapper())
    .mcpEndpoint("/mcp")
    .disallowDelete(false)
    .vertx(vertx)
    .build();
```

### 3. Integrate with Your Vert.x Application

Use the provided Verticle for easy integration:

```java
import io.vertx.ext.mcp.McpVerticle;

// Deploy the MCP verticle (pass transport and server specification)
vertx.deployVerticle(new McpVerticle(8080, transport, mcpServerSpec), ar -> {
    if (ar.succeeded()) {
        System.out.println("MCP Verticle deployed successfully");
    } else {
        System.err.println("Failed to deploy MCP Verticle: " + ar.cause());
    }
});
```

### 4. Complete Examples

> **Important**: The `McpServer` instance contains the actual MCP server implementation and must be kept in scope. The examples below show the recommended approach of passing both the transport and server to the `McpVerticle`, which manages the lifecycle for you. This prevents the server from being garbage collected.

#### Example A: Legacy HTTP+SSE Transport

Here's a complete working example using the legacy HTTP+SSE transport:

```java
import io.vertx.core.Vertx;
import io.vertx.ext.mcp.McpVerticle;
import io.vertx.ext.mcp.transport.VertxMcpSseServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

public class LegacyMcpServerExample {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        
        // Create MCP server with a simple calculator tool
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

        // Create legacy HTTP+SSE transport
        var transport = VertxMcpSseServerTransportProvider.builder()
            .baseUrl("http://localhost:8080")
            .messageEndpoint("/mcp/message")
            .sseEndpoint("/mcp/sse")
            .keepAliveInterval(Duration.ofSeconds(30))
            .objectMapper(new ObjectMapper())
            .vertx(vertx)
            .build();

        // Create MCP server specification (don't call .build() yet)
        var mcpServerSpec = McpServer.async(transport)
            .serverInfo("calculator-server", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(calculatorTool);

        // Deploy the MCP verticle (pass transport and server specification)
        vertx.deployVerticle(new McpVerticle(8080, transport, mcpServerSpec), ar -> {
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

#### Example B: New Streamable HTTP Transport

Here's a complete working example using the new MCP 2025-06-18 Streamable HTTP transport:

```java
import io.vertx.core.Vertx;
import io.vertx.ext.mcp.McpVerticle;
import io.vertx.ext.mcp.transport.VertxMcpStreamableServerTransportProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

public class StreamableMcpServerExample {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        
        // Create MCP server with a simple calculator tool
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

        // Create Streamable HTTP transport
        var transport = VertxMcpStreamableServerTransportProvider.builder()
            .objectMapper(new ObjectMapper())
            .mcpEndpoint("/mcp")
            .disallowDelete(false)
            .vertx(vertx)
            .keepAliveInterval(Duration.ofSeconds(30))
            .build();

        // Create MCP server specification (don't call .build() yet)
        var mcpServerSpec = McpServer.async(transport)
            .serverInfo("calculator-server", "1.0.0")
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(calculatorTool);

        // Deploy the MCP verticle (pass transport and server specification)
        vertx.deployVerticle(new McpVerticle(8080, transport, mcpServerSpec), ar -> {
            if (ar.succeeded()) {
                System.out.println("MCP Server started on port 8080");
                System.out.println("Streamable HTTP endpoint: http://localhost:8080/mcp");
                System.out.println("Supports: GET (SSE), POST (messages), DELETE (sessions)");
            } else {
                System.err.println("Failed to start MCP Server: " + ar.cause());
            }
        });
    }
}
```



## Configuration Options

### Legacy HTTP+SSE Transport

The `VertxMcpSseServerTransportProvider` supports several configuration options:

- **`baseUrl`**: Base URL for your server (required)
- **`messageEndpoint`**: Endpoint for receiving JSON-RPC messages (default: `/message`)
- **`sseEndpoint`**: Endpoint for SSE connections (default: `/sse`)
- **`keepAliveInterval`**: Interval for keep-alive ping messages (default: 30 seconds)
- **`objectMapper`**: Jackson ObjectMapper for JSON serialization
- **`vertx`**: Vert.x instance

### Streamable HTTP Transport

The `VertxMcpStreamableServerTransportProvider` supports:

- **`objectMapper`**: Jackson ObjectMapper for JSON processing (required)
- **`mcpEndpoint`**: The MCP endpoint path (default: `/mcp`)
- **`disallowDelete`**: Whether to disable session deletion (default: `false`)
- **`vertx`**: Vert.x instance (required)
- **`keepAliveInterval`**: Interval for keep-alive ping messages (default: 30 seconds)

## Architecture

### Legacy HTTP+SSE Transport

The transport implements the MCP protocol using:

1. **SSE Connection** (`/sse`): Establishes Server-Sent Events connection for server-to-client communication
2. **Message Endpoint** (`/message`): Receives JSON-RPC messages from clients
3. **Session Management**: Automatically manages client sessions and cleanup
4. **Keep-alive**: Sends periodic ping messages to prevent connection timeouts

### Streamable HTTP Transport

The new Streamable HTTP transport follows the MCP 2025-06-18 specification:

1. **Single Endpoint** (`/mcp`): Handles all HTTP methods (GET, POST, DELETE)
2. **Session Management**: Uses MCP SDK session management with unique session IDs
3. **Stream Resumption**: Supports resuming broken connections via Last-Event-ID headers
4. **Protocol Compliance**: Full compliance with the latest MCP specification

## Testing

You can test your MCP server using any MCP client:

### Legacy Transport
- **SSE endpoint**: `http://localhost:8080/mcp/sse` for establishing connections
- **Message endpoint**: `http://localhost:8080/mcp/message?sessionId=<id>` for sending requests

### Streamable Transport
- **Single endpoint**: `http://localhost:8080/mcp` for all operations
- **GET**: Establish SSE listening streams
- **POST**: Send JSON-RPC messages
- **DELETE**: Terminate sessions

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

### Vert.x 5 Support
Support for Vert.x 5.x versions, including:
- Compatibility with Vert.x 5.x APIs
- Updated transport implementations for Vert.x 5
- Performance improvements leveraging new Vert.x 5 features

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
