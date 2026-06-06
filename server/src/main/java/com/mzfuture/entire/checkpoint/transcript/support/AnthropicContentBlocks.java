package com.mzfuture.entire.checkpoint.transcript.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/// Parses Anthropic-Messages-API-style content nodes (string or array of typed blocks).
/// Used by Cursor, Claude Code, and Droid transcript formats.
public final class AnthropicContentBlocks {

    private AnthropicContentBlocks() {}

    /// Concatenated visible text from all `text` blocks. Returns empty string if no text blocks.
    public static String extractText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        if (content == null) return "";
        if (content.isTextual()) {
            return content.asText("");
        }
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(textOrNull(block, "type"))) {
                    String t = block.has("text") ? block.get("text").asText("") : "";
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
        }
        return sb.toString().trim();
    }

    /// Concatenated thinking text from all `thinking` blocks, or null if none present.
    public static String extractReasoning(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        if (content == null || !content.isArray()) return null;
        for (JsonNode block : content) {
            if ("thinking".equals(textOrNull(block, "type"))) {
                String t = block.has("thinking") ? block.get("thinking").asText("") : "";
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    /// Tool-use blocks. Each block becomes a record with id, name, input. The `id` and `name`
    /// fall back to empty strings when missing so downstream code can still match by call id.
    public static List<ToolUseEntry> extractToolUses(JsonNode content) {
        List<ToolUseEntry> out = new ArrayList<>();
        if (content == null || !content.isArray()) return out;
        for (JsonNode block : content) {
            if ("tool_use".equals(textOrNull(block, "type"))) {
                String id = firstNonEmpty(textOrNull(block, "id"), textOrNull(block, "call_id"));
                String name = textOrNull(block, "name");
                JsonNode input = block.has("input") && !block.get("input").isNull()
                        ? block.get("input") : null;
                out.add(new ToolUseEntry(id == null ? "" : id,
                        name == null ? "" : name, input));
            }
        }
        return out;
    }

    /// Tool-result blocks. `entries` have the tool_use_id, the result content as a string,
    /// and the `is_error` flag. Used to backfill `tool_use.output`.
    public static List<ToolResultEntry> extractToolResults(JsonNode content) {
        List<ToolResultEntry> out = new ArrayList<>();
        if (content == null || !content.isArray()) return out;
        for (JsonNode block : content) {
            if ("tool_result".equals(textOrNull(block, "type"))) {
                String id = textOrNull(block, "tool_use_id");
                JsonNode r = block.get("content");
                String text = r == null ? "" : (r.isTextual() ? r.asText("") : r.toString());
                boolean isError = block.has("is_error") && block.get("is_error").asBoolean(false);
                out.add(new ToolResultEntry(id == null ? "" : id, text, isError));
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        if (v.isValueNode()) return v.asText();
        return null;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return a != null ? a : b;
    }

    public record ToolUseEntry(String id, String name, JsonNode input) {}
    public record ToolResultEntry(String id, String text, boolean isError) {}
}
