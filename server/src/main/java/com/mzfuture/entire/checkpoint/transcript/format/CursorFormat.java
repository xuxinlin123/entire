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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Cursor NDJSON: each non-empty line is `{role: "user"|"assistant", message: {...}}`.
/// Detection: non-empty first line is a JSON object with `role ∈ {user, assistant}` and `message`.
@Component
public class CursorFormat implements TranscriptFormat {

    private static final String NAME = "cursor-ndjson";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 75; }

    @Override
    public boolean matches(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject()) return false;
                JsonNode r = n.get("role");
                JsonNode m = n.get("message");
                if (r == null || !r.isTextual()) return false;
                if (m == null || !m.isObject()) return false;
                String role = r.asText();
                return "user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String p : raw.split("\\R")) {
            String s = p.trim();
            if (!s.isEmpty()) lines.add(s);
        }

        List<TranscriptMessageViewDTO> messageViews = new ArrayList<>();
        int stepsCount = 0;
        int toolUseCount = 0;
        for (String line : lines) {
            JsonNode n = objectMapper.readTree(line);
            TranscriptMessageViewDTO mv = parseLine(n);
            messageViews.add(mv);
            if ("user".equalsIgnoreCase(mv.getRole())) {
                stepsCount++;
            }
            toolUseCount += mv.getTools() == null ? 0 : mv.getTools().size();
        }
        if (toolUseCount == 0) {
            warnings.add("cursor: tool_use blocks absent; file_changes may be empty");
        }

        List<TranscriptFileChangeSummaryDTO> fileChanges = buildFileChanges(messageViews);

        NormalizedTranscriptDTO dto = new NormalizedTranscriptDTO();
        dto.setSchemaVersion(1);
        NormalizedTranscriptMetaDTO meta = new NormalizedTranscriptMetaDTO();
        meta.setSourceFormat(NAME);
        meta.setRawBytesLength(rawBytesLength);
        if (!warnings.isEmpty()) meta.setWarnings(new ArrayList<>(warnings));
        dto.setMeta(meta);
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messageViews);
        return dto;
    }

    private TranscriptMessageViewDTO parseLine(JsonNode root) {
        String roleRaw = root.path("role").asText("");
        String role = "user".equalsIgnoreCase(roleRaw) ? "user" : "assistant";
        JsonNode message = root.path("message");
        JsonNode content = message.path("content");

        TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
        mv.setId("");
        mv.setRole(role);
        mv.setText(AnthropicContentBlocks.extractText(content));

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

    private List<TranscriptFileChangeSummaryDTO> buildFileChanges(List<TranscriptMessageViewDTO> messages) {
        List<PathDelta> raw = new ArrayList<>();
        for (TranscriptMessageViewDTO msg : messages) {
            if (msg.getTools() == null) continue;
            for (TranscriptToolUseDTO tu : msg.getTools()) {
                String path = FilePathExtractor.extract(tu.getInput());
                if (path == null || path.isBlank()) continue;
                int[] d = estimateDelta(tu);
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
        if (lower.equals("strreplace") || lower.equals("search_replace") || lower.equals("replace")) {
            String o = in.has("old_string") && in.get("old_string").isTextual() ? in.get("old_string").asText("") : "";
            String n = in.has("new_string") && in.get("new_string").isTextual() ? in.get("new_string").asText("") : "";
            int oldLines = countLines(o), newLines = countLines(n);
            return new int[]{Math.max(0, newLines - oldLines), Math.max(0, oldLines - newLines)};
        }
        if (lower.equals("write") || lower.equals("apply_patch")) {
            String c = in.has("contents") && in.get("contents").isTextual() ? in.get("contents").asText("")
                    : in.has("content") && in.get("content").isTextual() ? in.get("content").asText("") : "";
            return new int[]{countLines(c), 0};
        }
        if (lower.equals("delete_file") || lower.equals("delete")) return new int[]{0, 1};
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

    private record PathDelta(String path, int additions, int deletions) {}
}
