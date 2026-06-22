package com.mzfuture.entire.checkpoint.transcript.format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final Pattern SHELL_REDIRECT =
            Pattern.compile("(?m)(?:^|[\\s;&|])(?:\\d?>|>>?)\\s*(\"[^\"]+\"|'[^']+'|[^\\s<>;&|]+)");
    private static final Pattern POWERSHELL_WRITE_COMMAND =
            Pattern.compile("(?i)\\b(?:New-Item|Set-Content|Add-Content|Out-File)\\b[^\\r\\n;]*");
    private static final Pattern POWERSHELL_PATH_ARG =
            Pattern.compile("(?i)-(?:LiteralPath|Path|FilePath|Name)\\s+(\"[^\"]+\"|'[^']+'|[^\\s;]+)");
    private static final Pattern TOUCH_COMMAND =
            Pattern.compile("(?m)(?:^|[\\s;&|])touch\\s+(?:--\\s+)?(\"[^\"]+\"|'[^']+'|[^\\s;&|]+)");

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
            String role = textOrNull(payload, "role");
            if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
                return;
            }
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
            addMessage(messages, role.toLowerCase(Locale.ROOT), text.toString().trim());
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

            // apply_patch: scan argument text for "*** Add File: ..." markers.
            if (name != null && name.toLowerCase(Locale.ROOT).equals("apply_patch") && argText != null) {
                scanApplyPatch(argText, fileChangeMap);
            } else if (argsNode != null && argsNode.isObject()) {
                JsonNode input = argsNode.path("input");
                if (input.isTextual()) scanApplyPatch(input.asText(""), fileChangeMap);
            }

            List<String> touchedFiles = extractTouchedFiles(name, argText, argsNode);
            addTouchedFilesToInput(argsNode, touchedFiles);
            for (String file : touchedFiles) {
                recordFileChange(fileChangeMap, file, 0, 0);
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
                addMessage(messages, "user", textOrNull(payload, "message"));
            }
            case "agent_message" -> {
                addMessage(messages, "assistant", textOrNull(payload, "message"));
            }
            case "token_count" -> { /* dropped: no DTO field for tokens */ }
            default -> { /* drop task_started/completed, turn_***, etc. */ }
        }
    }

    private void addMessage(List<TranscriptMessageViewDTO> messages, String role, String text) {
        if (!"user".equals(role) && !"assistant".equals(role)) return;
        if (text == null || text.isBlank()) return;
        String trimmed = text.trim();
        if (isInternalBootstrapMessage(trimmed)) return;

        String normalized = normalizeText(trimmed);
        int start = Math.max(0, messages.size() - 6);
        for (int i = messages.size() - 1; i >= start; i--) {
            TranscriptMessageViewDTO existing = messages.get(i);
            if (role.equalsIgnoreCase(existing.getRole())
                    && normalized.equals(normalizeText(existing.getText()))) {
                return;
            }
        }

        TranscriptMessageViewDTO mv = new TranscriptMessageViewDTO();
        mv.setRole(role);
        mv.setText(trimmed);
        messages.add(mv);
    }

    private static boolean isInternalBootstrapMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<permissions instructions>")
                || lower.contains("<environment_context>")
                || lower.startsWith("# agents.md\n")
                || lower.startsWith("# instructions\n");
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", " ");
    }

    private void scanApplyPatch(String text, Map<String, int[]> fileChangeMap) {
        if (text == null || text.isEmpty()) return;
        Matcher m = APPLY_PATCH_HEADER.matcher(text);
        while (m.find()) {
            String op = m.group(1);
            String path = m.group(2).trim();
            if (path.isEmpty()) continue;
            recordFileChange(fileChangeMap, path, op.equals("Add") ? 1 : 0, op.equals("Delete") ? 1 : 0);
        }
    }

    private List<String> extractTouchedFiles(String toolName, String argText, JsonNode argsNode) {
        List<String> files = new ArrayList<>();
        if (argText != null) {
            scanCommandText(argText, files);
            scanApplyPatchText(argText, files);
        }
        if (argsNode != null && argsNode.isObject()) {
            for (String key : List.of("command", "cmd", "script", "input")) {
                JsonNode v = argsNode.path(key);
                if (v.isTextual()) {
                    scanCommandText(v.asText(""), files);
                    scanApplyPatchText(v.asText(""), files);
                }
            }
        }
        if (toolName != null && toolName.toLowerCase(Locale.ROOT).equals("apply_patch")) {
            if (argText != null) scanApplyPatchText(argText, files);
        }
        return distinct(files);
    }

    private void scanApplyPatchText(String text, List<String> files) {
        if (text == null || text.isEmpty()) return;
        Matcher m = APPLY_PATCH_HEADER.matcher(text);
        while (m.find()) addCandidateFile(files, m.group(2));
    }

    private void scanCommandText(String command, List<String> files) {
        if (command == null || command.isBlank()) return;

        Matcher redirect = SHELL_REDIRECT.matcher(command);
        while (redirect.find()) addCandidateFile(files, redirect.group(1));

        Matcher ps = POWERSHELL_WRITE_COMMAND.matcher(command);
        while (ps.find()) {
            String commandSegment = ps.group();
            Matcher pathArg = POWERSHELL_PATH_ARG.matcher(commandSegment);
            while (pathArg.find()) addCandidateFile(files, pathArg.group(1));
        }

        Matcher touch = TOUCH_COMMAND.matcher(command);
        while (touch.find()) addCandidateFile(files, touch.group(1));
    }

    private static void addCandidateFile(List<String> files, String raw) {
        String path = cleanPath(raw);
        if (path == null) return;
        files.add(path);
    }

    private static String cleanPath(String raw) {
        if (raw == null) return null;
        String path = raw.trim();
        if ((path.startsWith("\"") && path.endsWith("\"")) || (path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1);
        }
        path = path.replace('\\', '/').trim();
        if (path.isEmpty()
                || ".".equals(path)
                || "..".equals(path)
                || path.startsWith("-")
                || path.contains("$(")
                || path.contains("`")) {
            return null;
        }
        return path;
    }

    private static List<String> distinct(List<String> files) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    private void addTouchedFilesToInput(JsonNode argsNode, List<String> touchedFiles) {
        if (argsNode == null || !argsNode.isObject() || touchedFiles.isEmpty()) return;
        ObjectNode obj = (ObjectNode) argsNode;
        if (!obj.has("touched_files")) {
            ArrayNode arr = objectMapper.createArrayNode();
            for (String file : touchedFiles) arr.add(file);
            obj.set("touched_files", arr);
        }
        if (!obj.has("path") && touchedFiles.size() == 1) {
            obj.put("path", touchedFiles.get(0));
        }
    }

    private static void recordFileChange(Map<String, int[]> fileChangeMap, String path, int additions, int deletions) {
        String clean = cleanPath(path);
        if (clean == null) return;
        fileChangeMap.compute(clean, (k, v) -> {
            if (v == null) return new int[]{additions, deletions};
            v[0] += additions;
            v[1] += deletions;
            return v;
        });
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        JsonNode v = node.get(field);
        return v.isValueNode() ? v.asText() : null;
    }
}
