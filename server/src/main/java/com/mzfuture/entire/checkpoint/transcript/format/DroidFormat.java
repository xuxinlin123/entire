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

/// Factory AI Droid NDJSON: each non-empty line is `{type: "message", message: {role, content}}`
/// or `{type: "session_start", ...}` (dropped). Inner `message` is Anthropic-API-shaped.
@Component
public class DroidFormat implements TranscriptFormat {

    private static final String NAME = "droid-ndjson";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 70; }

    @Override
    public boolean matches(String raw) {
        // Disambiguate from Pi: Pi's first non-empty line is `type:"session"` with `version`.
        // If we see that, this is Pi, not Droid.
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (n.isObject() && "session".equals(textOrNull(n, "type")) && n.has("version")) {
                    return false;
                }
            } catch (Exception e) {
                // fall through to second pass
            }
            break;
        }
        // Scan remaining lines for a `type:"message"` envelope with inner `message.role`.
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject()) continue;
                if (!"message".equals(textOrNull(n, "type"))) continue;
                JsonNode m = n.get("message");
                if (m != null && m.isObject() && m.has("role")) return true;
            } catch (Exception e) {
                continue;
            }
        }
        return false;
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
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
            if (!"message".equals(textOrNull(n, "type"))) continue;
            JsonNode message = n.path("message");
            if (!message.isObject()) continue;
            String messageId = textOrNull(message, "id");

            if (messageId != null && idToIndex.containsKey(messageId)) {
                int idx = idToIndex.get(messageId);
                mergeInto(messages.get(idx), message);
            } else {
                TranscriptMessageViewDTO mv = buildMessage(message);
                messages.add(mv);
                if (messageId != null) idToIndex.put(messageId, messages.size() - 1);
                if ("user".equalsIgnoreCase(mv.getRole())) stepsCount++;
            }
        }

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

    /// Droid's session_start envelope carries `cwd` at the top level.
    private String extractCwd(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!"session_start".equals(textOrNull(n, "type"))) continue;
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

    private TranscriptMessageViewDTO buildMessage(JsonNode message) {
        JsonNode content = message.path("content");
        String roleRaw = textOrNull(message, "role");
        String role = "user".equalsIgnoreCase(roleRaw) ? "user" : "assistant";

        TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
        mv.setId(textOrNull(message, "id"));
        mv.setRole(role);
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

    private void mergeInto(TranscriptMessageViewDTO existing, JsonNode message) {
        JsonNode content = message.path("content");
        if (content == null || content.isNull() || content.isMissingNode()) return;
        String newText = AnthropicContentBlocks.extractText(content);
        if (!newText.isEmpty()) {
            String cur = existing.getText() == null ? "" : existing.getText();
            if (!cur.contains(newText)) {
                existing.setText(cur.isEmpty() ? newText : cur + "\n" + newText);
            }
        }
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
        if (existing.getTools() != null) existing.setToolsCount(existing.getTools().size());
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
        if (lower.equals("edit") || lower.equals("multiedit") || lower.equals("notebookedit")
                || lower.equals("strreplace") || lower.equals("replace")) {
            String o = in.has("old_string") && in.get("old_string").isTextual() ? in.get("old_string").asText("") : "";
            String n = in.has("new_string") && in.get("new_string").isTextual() ? in.get("new_string").asText("") : "";
            int oldLines = countLines(o), newLines = countLines(n);
            return new int[]{Math.max(0, newLines - oldLines), Math.max(0, oldLines - newLines)};
        }
        if (lower.equals("create") || lower.equals("write") || lower.equals("apply_patch")) {
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
