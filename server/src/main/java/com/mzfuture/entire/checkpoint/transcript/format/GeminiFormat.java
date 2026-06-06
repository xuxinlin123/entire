package com.mzfuture.entire.checkpoint.transcript.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptMetaDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileChangeSummaryDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptMessageViewDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptToolUseDTO;
import com.mzfuture.entire.checkpoint.transcript.TranscriptFormat;
import com.mzfuture.entire.checkpoint.transcript.support.FilePathExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Gemini CLI single JSON: `{sessionId, projectHash, startTime, lastUpdated, messages: [...]}`.
/// Detection: trimmed text parses as a single JSON object with `sessionId` AND `messages` (array),
/// and NO `info` key (that would be OpenCode).
@Component
public class GeminiFormat implements TranscriptFormat {

    private static final String NAME = "gemini-json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 90; }

    @Override
    public boolean matches(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            return root.isObject()
                    && !root.has("info")
                    && root.has("sessionId")
                    && root.has("messages")
                    && root.get("messages").isArray();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        Map<String, int[]> fileChangeMap = new LinkedHashMap<>();
        List<TranscriptMessageViewDTO> messageViews = new ArrayList<>();
        int stepsCount = 0;

        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode msg : messages) {
                String type = textOrNull(msg, "type");
                if ("info".equals(type)) continue;

                String role;
                if ("user".equals(type)) {
                    role = "user";
                    stepsCount++;
                } else {
                    role = "assistant";
                }

                StringBuilder textParts = new StringBuilder();
                JsonNode content = msg.path("content");
                if (content.isTextual()) {
                    textParts.append(content.asText(""));
                } else if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("text".equals(textOrNull(block, "type"))) {
                            String t = block.has("text") ? block.get("text").asText("") : "";
                            if (textParts.length() > 0) textParts.append('\n');
                            textParts.append(t);
                        }
                    }
                }

                StringBuilder reasoning = new StringBuilder();
                JsonNode thoughts = msg.path("thoughts");
                if (thoughts.isArray()) {
                    for (JsonNode th : thoughts) {
                        String d = textOrNull(th, "description");
                        if (d != null && !d.isEmpty()) {
                            if (reasoning.length() > 0) reasoning.append('\n');
                            reasoning.append(d);
                        }
                    }
                }

                List<TranscriptToolUseDTO> tools = new ArrayList<>();
                JsonNode toolCalls = msg.path("toolCalls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        TranscriptToolUseDTO tu = new TranscriptToolUseDTO();
                        tu.setCallID(textOrNull(tc, "id"));
                        tu.setTool(textOrNull(tc, "name"));
                        JsonNode args = tc.path("args");
                        if (!args.isMissingNode() && !args.isNull()) tu.setInput(args);
                        JsonNode result = tc.path("result");
                        if (result.isArray()) {
                            tu.setOutput(result);
                        }
                        tu.setTitle(textOrNull(tc, "displayName"));
                        tools.add(tu);

                        String path = FilePathExtractor.extract(args);
                        if (path == null) {
                            JsonNode rd = tc.path("resultDisplay");
                            if (rd.isObject()) {
                                path = FilePathExtractor.extract(rd);
                            }
                        }
                        if (path != null && !path.isBlank()) {
                            fileChangeMap.compute(path, (k, v) -> {
                                if (v == null) return new int[]{1, 0};
                                v[0] += 1;
                                return v;
                            });
                        }
                    }
                }

                TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                mv.setId(textOrNull(msg, "id"));
                mv.setRole(role);
                mv.setText(textParts.toString().trim());
                if (reasoning.length() > 0) mv.setReasoning(reasoning.toString().trim());
                JsonNode time = msg.path("timestamp");
                if (time.isNumber()) mv.setCreatedAt(time.asLong());
                mv.setTools(tools);
                mv.setToolsCount(tools.size());
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
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messageViews);
        return dto;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
