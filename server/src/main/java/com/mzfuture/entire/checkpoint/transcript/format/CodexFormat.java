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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Codex NDJSON: each line is `{type, payload, timestamp}`. Two-level dispatch on
/// `(type, payload.type)`. `token_count` events are dropped (no DTO field for tokens).
@Component
public class CodexFormat implements TranscriptFormat {

    private static final String NAME = "codex-ndjson";
    private static final Pattern APPLY_PATCH_HEADER =
            Pattern.compile("\\*\\*\\* (Add|Update|Delete) File: (.+)");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return NAME; }
    @Override public int priority() { return 60; }

    @Override
    public boolean matches(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!n.isObject() || !n.has("type") || !n.has("payload")) continue;
                String type = n.get("type").asText();
                if (type.equals("session_meta") || type.equals("response_item")
                        || type.equals("event_msg") || type.equals("turn_context")) {
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
        List<TranscriptMessageViewDTO> messages = new ArrayList<>();
        Map<String, TranscriptToolUseDTO> pendingToolByCallId = new LinkedHashMap<>();
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
            JsonNode payload = n.path("payload");
            if (!payload.isObject()) continue;

            switch (type == null ? "" : type) {
                case "response_item" -> handleResponseItem(payload, messages, pendingToolByCallId, fileChangeMap);
                case "event_msg" -> handleEventMsg(payload, messages);
                case "session_meta", "turn_context" -> { /* drop */ }
                default -> { /* drop unknown types */ }
            }
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
        dto.setProjectRoot(projectRoot);
        dto.setStepsCount(stepsCount);
        dto.setFileChanges(fileChanges);
        dto.setMessages(messages);
        return dto;
    }

    /// Codex's session_meta envelope carries `cwd` in its payload.
    private String extractCwd(String raw) {
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = objectMapper.readTree(t);
                if (!"session_meta".equals(textOrNull(n, "type"))) continue;
                JsonNode cwd = n.path("payload").path("cwd");
                if (cwd.isTextual() && !cwd.asText().isEmpty()) {
                    return cwd.asText();
                }
            } catch (Exception e) {
                // keep scanning
            }
        }
        return null;
    }

    private void handleResponseItem(JsonNode payload,
                                    List<TranscriptMessageViewDTO> messages,
                                    Map<String, TranscriptToolUseDTO> pendingToolByCallId,
                                    Map<String, int[]> fileChangeMap) {
        String ptype = textOrNull(payload, "type");
        if ("message".equals(ptype)) {
            TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
            String role = textOrNull(payload, "role");
            mv.setRole("user".equalsIgnoreCase(role) ? "user" : "assistant");
            StringBuilder text = new StringBuilder();
            JsonNode content = payload.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String bt = textOrNull(block, "type");
                    String t = "";
                    if ("text".equals(bt) || "input_text".equals(bt) || "output_text".equals(bt)) {
                        t = block.has("text") ? block.get("text").asText("") : "";
                    }
                    if (!t.isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(t);
                    }
                }
            }
            mv.setText(text.toString().trim());
            messages.add(mv);
        } else if ("function_call".equals(ptype) || "custom_tool_call".equals(ptype)) {
            String callId = textOrNull(payload, "call_id");
            String name = textOrNull(payload, "name");
            JsonNode argsNode = payload.path("arguments");
            String argText = null;
            if (argsNode.isTextual()) {
                argText = argsNode.asText("");
                try {
                    argsNode = objectMapper.readTree(argText);
                } catch (Exception e) {
                    argsNode = objectMapper.createObjectNode();
                }
            }
            TranscriptToolUseDTO tu = new TranscriptToolUseDTO();
            tu.setCallID(callId == null ? "" : callId);
            tu.setTool(name == null ? "" : name);
            tu.setInput(argsNode);
            if (callId != null) pendingToolByCallId.put(callId, tu);

            // apply_patch: scan argument text for "*** Add File: ..." markers
            if (name != null && name.toLowerCase(Locale.ROOT).equals("apply_patch") && argText != null) {
                scanApplyPatch(argText, fileChangeMap);
            } else if (argsNode != null && argsNode.isObject()) {
                JsonNode input = argsNode.path("input");
                if (input.isTextual()) scanApplyPatch(input.asText(""), fileChangeMap);
            }

            // Attach to most recent assistant message (or create a stub).
            TranscriptMessageViewDTO last;
            if (!messages.isEmpty() && "assistant".equalsIgnoreCase(messages.get(messages.size() - 1).getRole())) {
                last = messages.get(messages.size() - 1);
            } else {
                last = new TranscriptMessageViewDTO();
                last.setRole("assistant");
                last.setText("");
                messages.add(last);
            }
            if (last.getTools() == null) last.setTools(new ArrayList<>());
            last.getTools().add(tu);
            last.setToolsCount(last.getTools().size());
        } else if ("function_call_output".equals(ptype) || "custom_tool_call_output".equals(ptype)) {
            String callId = textOrNull(payload, "call_id");
            JsonNode output = payload.path("output");
            if (output.isTextual() || output.isObject()) {
                TranscriptToolUseDTO tu = pendingToolByCallId.get(callId);
                if (tu != null) tu.setOutput(output);
            }
        } else if ("reasoning".equals(ptype)) {
            // Drop encrypted reasoning; attach nothing.
        }
    }

    private void handleEventMsg(JsonNode payload,
                                List<TranscriptMessageViewDTO> messages) {
        String ptype = textOrNull(payload, "type");
        switch (ptype == null ? "" : ptype) {
            case "user_message" -> {
                TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                mv.setRole("user");
                mv.setText(textOrNull(payload, "message"));
                messages.add(mv);
            }
            case "agent_message" -> {
                TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
                mv.setRole("assistant");
                mv.setText(textOrNull(payload, "message"));
                messages.add(mv);
            }
            case "token_count" -> { /* dropped: no DTO field for tokens */ }
            default -> { /* drop task_started/completed, turn_***, etc. */ }
        }
    }

    private void scanApplyPatch(String text, Map<String, int[]> fileChangeMap) {
        if (text == null || text.isEmpty()) return;
        Matcher m = APPLY_PATCH_HEADER.matcher(text);
        while (m.find()) {
            String op = m.group(1);
            String path = m.group(2).trim();
            if (path.isEmpty()) continue;
            fileChangeMap.compute(path, (k, v) -> {
                if (v == null) return new int[]{op.equals("Add") ? 1 : 0, op.equals("Delete") ? 1 : 0};
                if (op.equals("Add")) v[0]++;
                else if (op.equals("Delete")) v[1]++;
                return v;
            });
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
