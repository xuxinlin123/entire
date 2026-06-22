package com.mzfuture.entire.checkpoint.transcript.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Extracts file paths from a tool-use `input` JSON object, trying common key names
/// (`file_path`, `path`, `file`, `target_file`, `target_directory`, `notebook_path`)
/// and Codex's synthetic `touched_files` list.
public final class FilePathExtractor {

    private FilePathExtractor() {}

    private static final String[] KEYS = {
            "file_path", "path", "file", "target_file", "target_directory", "notebook_path"
    };

    public static String extract(JsonNode input) {
        List<String> paths = extractAll(input);
        return paths.isEmpty() ? null : paths.get(0);
    }

    public static List<String> extractAll(JsonNode input) {
        List<String> paths = new ArrayList<>();
        if (input == null || !input.isObject()) return Collections.emptyList();
        for (String k : KEYS) {
            if (input.has(k) && input.get(k).isTextual()) {
                String p = input.get(k).asText("").trim();
                if (!p.isEmpty()) paths.add(p.replace('\\', '/'));
            }
        }
        JsonNode touched = input.get("touched_files");
        if (touched != null && touched.isArray()) {
            for (JsonNode n : touched) {
                if (n.isTextual()) {
                    String p = n.asText("").trim();
                    if (!p.isEmpty()) paths.add(p.replace('\\', '/'));
                }
            }
        }
        return paths.stream().distinct().toList();
    }
}
