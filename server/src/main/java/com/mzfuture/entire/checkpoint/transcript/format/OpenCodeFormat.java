package com.mzfuture.entire.checkpoint.transcript.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptMetaDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileChangeSummaryDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileDiffDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptMessageViewDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptToolUseDTO;
import com.mzfuture.entire.checkpoint.transcript.TranscriptFormat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// OpenCode single JSON: `{info: {...}, messages: [{info, parts, summary}, ...]}`.
/// Detection: trimmed text parses as a single JSON object with `info` AND `messages` (array).
@Component
public class OpenCodeFormat implements TranscriptFormat {

    private static final String NAME = "opencode-json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 100; }

    @Override
    public boolean matches(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            return root.isObject()
                    && root.has("info")
                    && root.has("messages")
                    && root.get("messages").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode info = root.path("info");
        String title = textOrNull(info, "title");
        String projectRoot = textOrNull(info, "directory");

        Map<String, int[]> fileChangeMap = new LinkedHashMap<>();
        List<TranscriptMessageViewDTO> messageViews = new ArrayList<>();
        int stepsCount = 0;

        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            warnings.add("OpenCode: missing messages array");
        } else {
            for (JsonNode msg : messages) {
                JsonNode infoN = msg.path("info");
                String roleStr = textOrNull(infoN, "role");
                boolean isUser = "user".equalsIgnoreCase(roleStr);
                if (isUser) stepsCount++;

                JsonNode parts = msg.path("parts");
                StringBuilder textParts = new StringBuilder();
                String reasoning = null;
                List<TranscriptToolUseDTO> tools = new ArrayList<>();

                if (parts.isArray()) {
                    for (JsonNode p : parts) {
                        String type = textOrNull(p, "type");
                        if ("text".equals(type)) {
                            String t = p.has("text") ? p.get("text").asText("") : "";
                            if (textParts.length() > 0) textParts.append('\n');
                            textParts.append(t);
                        } else if ("reasoning".equals(type)) {
                            reasoning = p.has("text") ? p.get("text").asText("") : null;
                        } else if ("tool".equals(type)) {
                            TranscriptToolUseDTO tu = new TranscriptToolUseDTO();
                            tu.setCallID(textOrNull(p, "callID"));
                            tu.setTool(textOrNull(p, "tool"));
                            JsonNode state = p.path("state");
                            if (!state.isMissingNode() && !state.isNull()) {
                                if (state.has("input")) tu.setInput(state.get("input"));
                                if (state.has("output")) tu.setOutput(state.get("output"));
                                tu.setTitle(textOrNull(state, "title"));
                            }
                            tools.add(tu);
                        }
                    }
                }

                JsonNode summary = infoN.path("summary");
                List<TranscriptFileDiffDTO> diffs = null;
                if (summary.has("diffs") && summary.get("diffs").isArray()) {
                    diffs = new ArrayList<>();
                    for (JsonNode d : summary.get("diffs")) {
                        TranscriptFileDiffDTO fd = new TranscriptFileDiffDTO();
                        String file = d.has("file") ? d.get("file").asText("") : "";
                        fd.setFile(stripProjectRoot(file, projectRoot));
                        fd.setBefore(textOrNull(d, "before"));
                        fd.setAfter(textOrNull(d, "after"));
                        int add = d.has("additions") ? d.get("additions").asInt() : 0;
                        int del = d.has("deletions") ? d.get("deletions").asInt() : 0;
                        fd.setAdditions(add);
                        fd.setDeletions(del);
                        diffs.add(fd);
                        String rel = fd.getFile();
                        fileChangeMap.compute(rel, (k, v) -> {
                            if (v == null) return new int[]{add, del};
                            v[0] += add; v[1] += del;
                            return v;
                        });
                    }
                }

                TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                mv.setId(textOrNull(infoN, "id"));
                mv.setRole(isUser ? "user" : "assistant");
                mv.setText(textParts.toString().trim());
                mv.setTaskTitle(textOrNull(summary, "title"));
                mv.setReasoning(reasoning);
                JsonNode time = infoN.path("time");
                if (time.has("created")) mv.setCreatedAt(time.get("created").asLong());
                mv.setTools(tools);
                mv.setToolsCount(tools.size());
                mv.setDiffs(diffs);
                messageViews.add(mv);
            }
        }

        List<TranscriptFileChangeSummaryDTO> fileChanges = new ArrayList<>();
        for (Map.Entry<String, int[]> e : fileChangeMap.entrySet()) {
            TranscriptFileChangeSummaryDTO s = new TranscriptFileChangeSummaryDTO();
            s.setFile(e.getKey());
            s.setAdditions(e.getValue()[0]);
            s.setDeletions(e.getValue()[1]);
            fileChanges.add(s);
        }

        NormalizedTranscriptDTO dto = new NormalizedTranscriptDTO();
        dto.setSchemaVersion(1);
        NormalizedTranscriptMetaDTO meta = new NormalizedTranscriptMetaDTO();
        meta.setSourceFormat(NAME);
        meta.setRawBytesLength(rawBytesLength);
        if (!warnings.isEmpty()) meta.setWarnings(new ArrayList<>(warnings));
        dto.setMeta(meta);
        dto.setTitle(title);
        dto.setProjectRoot(projectRoot);
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messageViews);
        return dto;
    }

    private static String stripProjectRoot(String filePath, String projectRoot) {
        if (projectRoot == null || projectRoot.isEmpty()) return filePath;
        String normalized = filePath.replace('\\', '/');
        String root = projectRoot.replace('\\', '/').replaceAll("/$", "");
        if (normalized.startsWith(root + "/")) return normalized.substring(root.length() + 1);
        return filePath;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
