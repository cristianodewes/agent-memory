package com.agentmemory.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Small JSON helpers shared by the MCP tools (issue #17): building the JSON-Schema {@code Map} an
 * MCP {@code Tool} advertises as its input schema, and serializing a structured tool result to a
 * pretty JSON string wrapped as MCP text content. Uses Spring Boot 4's Jackson 3
 * ({@code tools.jackson}) mapper — the same generation the MCP SDK's Jackson-3 binding uses.
 *
 * <p>MCP tool results are content blocks; returning a single JSON text block (rather than only the
 * MCP {@code structuredContent} field) keeps every client — including ones that just render text —
 * able to read the result, while remaining trivially machine-parseable.
 */
final class McpJson {

    private final JsonMapper mapper;

    McpJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** A required string property descriptor for an input schema. */
    static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** An integer property descriptor for an input schema. */
    static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /**
     * Build a JSON-Schema object {@code Map} for a tool's input.
     *
     * @param properties ordered property-name → descriptor map.
     * @param required   the names of required properties.
     * @return the schema map (type=object, properties, required, additionalProperties=false).
     */
    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Serialize {@code value} to pretty JSON and wrap it as a successful tool result. */
    CallToolResult ok(Object value) {
        return CallToolResult.builder().addTextContent(toJson(value)).isError(false).build();
    }

    /** A tool error result carrying {@code message} (MCP {@code isError=true}). */
    static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            // Serialization of our own small DTOs should never fail; surface it loudly if it does.
            throw new IllegalStateException("failed to serialize MCP tool result: " + e.getMessage(), e);
        }
    }
}
