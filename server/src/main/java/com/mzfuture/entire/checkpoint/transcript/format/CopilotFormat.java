package com.mzfuture.entire.checkpoint.transcript.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptMetaDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileChangeSummaryDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptMessageViewDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptToolUseDTO;
import com.mzfuture.entire.checkpoint.transcript.TranscriptFormat;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Copilot CLI NDJSON event stream: `{type, data, id, parentId, timestamp}`.
/// Simplified linear event merge: emit messages in event order, attach toolRequests to
/// the most recent assistant, backfill tool execution outputs by toolCallId. Does NOT
/// reconstruct the full parentId turn tree.
@Component
public class CopilotFormat implements TranscriptFormat {

    private static final String NAME = "copilot-cli-ndjson";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 50; }

    @Override
    public boolean matches(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject() || !n.has("type") || !n.has("data")) continue;
                String type = n.get("type").asText();
                if (type.equals("session.start") || type.equals("user.message")
                        || type.equals("assistant.message") || type.equals("tool.execution_complete")) {
                    return true;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return false;
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        warnings.add("copilot-cli: linear event merge; tool/turn ordering may be approximate");
        List<TranscriptMessageViewDTO> messages = new ArrayList<>();
        Map<String, TranscriptToolUseDTO> pendingByCallId = new LinkedHashMap<>();
        Map<String, int[]> fileChangeMap = new LinkedHashMap<>();
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
            JsonNode data = n.path("data");
            if (!data.isObject()) continue;

            switch (type == null ? "" : type) {
                case "user.message" -> {
                    TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                    mv.setRole("user");
                    mv.setText(data.path("content").asText(""));
                    messages.add(mv);
                    stepsCount++;
                }
                case "assistant.message" -> {
                    TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                    mv.setRole("assistant");
                    mv.setText(data.path("content").asText(""));
                    List<TranscriptToolUseDTO> tools = new ArrayList<>();
                    JsonNode toolRequests = data.path("toolRequests");
                    if (toolRequests.isArray()) {
                        for (JsonNode tr : toolRequests) {
                            TranscriptToolUseDTO tu = new TranscriptToolUseDTO();
                            tu.setCallID(textOrNull(tr, "toolCallId"));
                            tu.setTool(textOrNull(tr, "name"));
                            JsonNode args = tr.path("arguments");
                            if (!args.isMissingNode() && !args.isNull()) tu.setInput(args);
                            tools.add(tu);
                            if (tu.getCallID() != null) pendingByCallId.put(tu.getCallID(), tu);
                        }
                    }
                    mv.setTools(tools);
                    mv.setToolsCount(tools.size());
                    messages.add(mv);
                }
                case "tool.execution_complete" -> {
                    String callId = textOrNull(data, "toolCallId");
                    TranscriptToolUseDTO tu = callId == null ? null : pendingByCallId.get(callId);
                    if (tu != null) {
                        JsonNode result = data.path("result");
                        if (!result.isMissingNode() && !result.isNull()) tu.setOutput(result);
                    }
                    JsonNode tel = data.path("toolTelemetry");
                    JsonNode props = tel.path("properties");
                    if (props.isObject() && props.has("filePaths")) {
                        String fp = props.get("filePaths").asText("");
                        if (!fp.isEmpty()) {
                            try {
                                JsonNode arr = objectMapper.readTree(fp);
                                if (arr.isArray()) {
                                    for (JsonNode p : arr) {
                                        String path = p.asText("");
                                        if (!path.isEmpty()) {
                                            fileChangeMap.compute(path, (k, v) -> {
                                                if (v == null) return new int[]{1, 0};
                                                v[0]++; return v;
                                            });
                                        }
                                    }
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                }
                case "session.start", "session.info", "session.shutdown",
                        "assistant.turn_start", "assistant.turn_end",
                        "tool.execution_start", "hook.start", "hook.end" -> { /* drop */ }
                default -> { /* drop unknown */ }
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
        dto.setProjectRoot(projectRoot);
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messages);
        return dto;
    }

    /// Copilot CLI's `session.start` event carries cwd under `data.context.cwd`.
    private String extractCwd(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!"session.start".equals(textOrNull(n, "type"))) continue;
                JsonNode cwd = n.path("data").path("context").path("cwd");
                if (cwd.isTextual() && !cwd.asText().isEmpty()) {
                    return cwd.asText();
                }
            } catch (Exception e) {
                // keep scanning
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
