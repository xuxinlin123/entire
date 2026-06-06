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
import java.util.Locale;
import java.util.Map;

/// Pi NDJSON: each line is `{type, id, parentId, timestamp, message?}`. Only `type=="message"`
/// entries produce content. `message.content` may be a string or an array of typed blocks
/// (`text`, `toolCall`). Branch filtering NOT implemented (all branches included in order).
@Component
public class PiFormat implements TranscriptFormat {

    private static final String NAME = "pi-ndjson";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 40; }

    @Override
    public boolean matches(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject()) return false;
                if (!"session".equals(textOrNull(n, "type"))) return false;
                return n.has("version");
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings) throws Exception {
        warnings.add("pi: branch filtering not implemented; abandoned branches included");
        List<TranscriptMessageViewDTO> messages = new ArrayList<>();
        Map<String, int[]> fileChangeMap = new LinkedHashMap<>();
        int stepsCount = 0;

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
            String role = textOrNull(message, "role");
            if (role == null) continue;

            JsonNode content = message.path("content");
            StringBuilder text = new StringBuilder();
            List<TranscriptToolUseDTO> tools = new ArrayList<>();

            if (content.isTextual()) {
                text.append(content.asText(""));
            } else if (content.isArray()) {
                for (JsonNode block : content) {
                    String bt = textOrNull(block, "type");
                    if ("text".equals(bt)) {
                        String t = block.has("text") ? block.get("text").asText("") : "";
                        if (text.length() > 0) text.append('\n');
                        text.append(t);
                    } else if ("toolCall".equals(bt)) {
                        TranscriptToolUseDTO tu = new TranscriptToolUseDTO();
                        tu.setCallID(textOrNull(block, "id"));
                        tu.setTool(textOrNull(block, "name"));
                        JsonNode args = block.path("arguments");
                        if (!args.isMissingNode() && !args.isNull()) tu.setInput(args);
                        tools.add(tu);

                        String lowerName = (tu.getTool() == null ? "" : tu.getTool()).toLowerCase(Locale.ROOT);
                        if (lowerName.equals("write") || lowerName.equals("edit")) {
                            String path = FilePathExtractor.extract(args);
                            if (path != null) {
                                fileChangeMap.compute(path, (k, v) -> {
                                    if (v == null) return new int[]{1, 0};
                                    v[0]++; return v;
                                });
                            }
                        }
                    }
                }
            }

            // toolResult role is mapped to assistant so the user can see the raw result.
            String mappedRole = switch (role) {
                case "user" -> "user";
                case "toolResult" -> "assistant";
                default -> "assistant";
            };

            TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
            mv.setRole(mappedRole);
            mv.setText(text.toString().trim());
            mv.setTools(tools);
            mv.setToolsCount(tools.size());
            messages.add(mv);
        }
        for (TranscriptMessageViewDTO mv : messages) {
            if ("user".equalsIgnoreCase(mv.getRole())) stepsCount++;
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
        dto.setMessages(messages);
        return dto;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
