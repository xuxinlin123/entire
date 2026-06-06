package com.mzfuture.entire.checkpoint.transcript.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptMetaDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileChangeSummaryDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptMessageViewDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptToolUseDTO;
import com.mzfuture.entire.checkpoint.transcript.TranscriptFormat;
import com.mzfuture.entire.checkpoint.transcript.support.AnthropicContentBlocks;
import com.mzfuture.entire.checkpoint.transcript.support.FilePathExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Claude Code NDJSON: each non-empty line is `{type, message, ...}`. Keeps only
/// `type == "user"` and `type == "assistant"`. Merges streaming duplicates by
/// `message.id`. Folds `tool_result` content back into the matching prior tool_use.
@Component
public class ClaudeCodeFormat implements TranscriptFormat {

    private static final String NAME = "claude-code-ndjson";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 80; }

    @Override
    public boolean matches(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject()) continue;
                JsonNode type = n.get("type");
                JsonNode message = n.get("message");
                if (type == null || !type.isTextual()) continue;
                if (message == null || !message.isObject()) continue;
                String s = type.asText();
                if ("user".equals(s) || "assistant".equals(s)) return true;
            } catch (Exception e) {
                continue;
            }
        }
        return false;
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        // Streaming dedup: order-preserving map from message.id -> index in messages.
        // When a new id arrives, the previous id is already merged.
        Map<String, Integer> idToIndex = new HashMap<>();
        List<TranscriptMessageViewDTO> messages = new ArrayList<>();
        int stepsCount = 0;
        String projectRoot = extractCwd(raw);

        for (String rawLine : raw.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            JsonNode n;
            try {
                n = objectMapper.readTree(line);
            } catch (Exception e) {
                continue;
            }
            String type = textOrNull(n, "type");
            if (!"user".equals(type) && !"assistant".equals(type)) continue;
            JsonNode message = n.path("message");
            String messageId = textOrNull(message, "id");

            if (messageId != null && idToIndex.containsKey(messageId)) {
                int idx = idToIndex.get(messageId);
                mergeInto(messages.get(idx), n);
            } else {
                TranscriptMessageViewDTO mv = buildMessage(n, type);
                messages.add(mv);
                if (messageId != null) idToIndex.put(messageId, messages.size() - 1);
                if ("user".equalsIgnoreCase(mv.getRole())) stepsCount++;
            }
        }

        // Note on tool_result folding: when a user line carries tool_result content blocks,
        // we could backfill the matching assistant tool_use.output. We don't, because
        // `buildMessage` already folded the tool_result text into the user message's `text`
        // field. The result is visible in the user turn; matching it back to a tool card on
        // a prior assistant turn is a follow-up enhancement.

        List<TranscriptFileChangeSummaryDTO> fileChanges = buildFileChanges(messages);

        NormalizedTranscriptDTO dto = new NormalizedTranscriptDTO();
        dto.setSchemaVersion(1);
        NormalizedTranscriptMetaDTO meta = new NormalizedTranscriptMetaDTO();
        meta.setSourceFormat(NAME);
        meta.setRawBytesLength(rawBytesLength);
        if (!warnings.isEmpty()) meta.setWarnings(new ArrayList<>(warnings));
        dto.setMeta(meta);
        dto.setProjectRoot(projectRoot);
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messages);
        return dto;
    }

    /// Claude Code stamps `cwd` at the top of every line; grab the first one we see so
    /// downstream code can translate tool-input absolute paths into git-relative ones.
    private String extractCwd(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                JsonNode cwd = n.get("cwd");
                if (cwd != null && cwd.isTextual() && !cwd.asText().isEmpty()) {
                    return cwd.asText();
                }
            } catch (Exception e) {
                // keep scanning
            }
        }
        return null;
    }

    private TranscriptMessageViewDTO buildMessage(JsonNode root, String type) {
        JsonNode message = root.path("message");
        JsonNode content = message.path("content");

        TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
        mv.setId(textOrNull(message, "id"));
        mv.setRole("user".equals(type) ? "user" : "assistant");
        mv.setText(AnthropicContentBlocks.extractText(content));
        mv.setReasoning(AnthropicContentBlocks.extractReasoning(content));

        List<TranscriptToolUseDTO> tools = new ArrayList<>();
        for (AnthropicContentBlocks.ToolUseEntry tu : AnthropicContentBlocks.extractToolUses(content)) {
            TranscriptToolUseDTO dto = new TranscriptToolUseDTO();
            dto.setCallID(tu.id());
            dto.setTool(tu.name());
            dto.setInput(tu.input());
            tools.add(dto);
        }
        mv.setTools(tools);
        mv.setToolsCount(tools.size());
        return mv;
    }

    private void mergeInto(TranscriptMessageViewDTO existing, JsonNode newLine) {
        JsonNode message = newLine.path("message");
        JsonNode content = message.path("content");
        if (content == null || content.isNull() || content.isMissingNode()) return;

        // Re-extract text and merge with existing.
        String newText = AnthropicContentBlocks.extractText(content);
        if (!newText.isEmpty()) {
            String cur = existing.getText() == null ? "" : existing.getText();
            if (!cur.contains(newText)) {
                existing.setText(cur.isEmpty() ? newText : cur + "\n" + newText);
            }
        }

        // Append new tool_use blocks (dedupe by id).
        for (AnthropicContentBlocks.ToolUseEntry tu : AnthropicContentBlocks.extractToolUses(content)) {
            boolean already = existing.getTools() != null && existing.getTools().stream()
                    .anyMatch(t -> tu.id().equals(t.getCallID()));
            if (!already) {
                TranscriptToolUseDTO dto = new TranscriptToolUseDTO();
                dto.setCallID(tu.id());
                dto.setTool(tu.name());
                dto.setInput(tu.input());
                if (existing.getTools() == null) existing.setTools(new ArrayList<>());
                existing.getTools().add(dto);
            }
        }
        if (existing.getTools() != null) {
            existing.setToolsCount(existing.getTools().size());
        }
    }

    private List<TranscriptFileChangeSummaryDTO> buildFileChanges(List<TranscriptMessageViewDTO> messages) {
        List<PathDelta> raw = new ArrayList<>();
        for (TranscriptMessageViewDTO msg : messages) {
            if (msg.getTools() == null) continue;
            for (TranscriptToolUseDTO tu : msg.getTools()) {
                String path = FilePathExtractor.extract(tu.getInput());
                if (path == null) continue;
                int[] d = estimateDelta(tu);
                if (d[0] == 0 && d[1] == 0) continue;
                raw.add(new PathDelta(path, d[0], d[1]));
            }
        }
        if (raw.isEmpty()) return Collections.emptyList();
        List<String> distinct = raw.stream().map(p -> p.path).distinct().toList();
        String dirPrefix = sharedDirectoryPrefix(distinct);
        Map<String, int[]> merged = new LinkedHashMap<>();
        for (PathDelta pd : raw) {
            String key = toRelative(pd.path, dirPrefix);
            merged.compute(key, (k, v) -> {
                if (v == null) return new int[]{pd.additions, pd.deletions};
                v[0] += pd.additions; v[1] += pd.deletions;
                return v;
            });
        }
        List<TranscriptFileChangeSummaryDTO> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : merged.entrySet()) {
            TranscriptFileChangeSummaryDTO s = new TranscriptFileChangeSummaryDTO();
            s.setFile(e.getKey());
            s.setAdditions(e.getValue()[0]);
            s.setDeletions(e.getValue()[1]);
            out.add(s);
        }
        return out;
    }

    private static int[] estimateDelta(TranscriptToolUseDTO tu) {
        String lower = tu.getTool() == null ? "" : tu.getTool().toLowerCase(Locale.ROOT);
        JsonNode in = tu.getInput();
        if (in == null || !in.isObject()) return new int[]{0, 0};
        if (lower.equals("edit") || lower.equals("strreplace") || lower.equals("search_replace")
                || lower.equals("replace") || lower.equals("notebookedit")) {
            String o = in.has("old_string") && in.get("old_string").isTextual() ? in.get("old_string").asText("") : "";
            String n = in.has("new_string") && in.get("new_string").isTextual() ? in.get("new_string").asText("") : "";
            int oldLines = countLines(o), newLines = countLines(n);
            return new int[]{Math.max(0, newLines - oldLines), Math.max(0, oldLines - newLines)};
        }
        if (lower.equals("write") || lower.equals("apply_patch")) {
            String c = "";
            if (in.has("content") && in.get("content").isTextual()) c = in.get("content").asText("");
            else if (in.has("contents") && in.get("contents").isTextual()) c = in.get("contents").asText("");
            return new int[]{countLines(c), 0};
        }
        return new int[]{0, 0};
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String sharedDirectoryPrefix(List<String> paths) {
        if (paths.isEmpty()) return "";
        if (paths.size() == 1) {
            String p = paths.get(0);
            int slash = p.lastIndexOf('/');
            return slash <= 0 ? "" : p.substring(0, slash + 1);
        }
        String first = paths.get(0);
        int minLen = first.length();
        for (String p : paths) minLen = Math.min(minLen, commonPrefixLength(first, p));
        String prefix = first.substring(0, minLen);
        int slash = prefix.lastIndexOf('/');
        return slash <= 0 ? "" : prefix.substring(0, slash + 1);
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private static String toRelative(String p, String dirPrefix) {
        return dirPrefix != null && !dirPrefix.isEmpty() && p.startsWith(dirPrefix)
                ? p.substring(dirPrefix.length()) : p;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }

    private record PathDelta(String path, int additions, int deletions) {}
}
