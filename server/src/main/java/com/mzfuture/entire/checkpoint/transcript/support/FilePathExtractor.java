package com.mzfuture.entire.checkpoint.transcript.support;

import com.fasterxml.jackson.databind.JsonNode;

/// Extracts a file path from a tool-use `input` JSON object, trying common key names
/// (`file_path`, `path`, `file`, `target_file`, `target_directory`, `notebook_path`).
/// Returns null when no usable path is present.
public final class FilePathExtractor {

    private FilePathExtractor() {}

    private static final String[] KEYS = {
            "file_path", "path", "file", "target_file", "target_directory", "notebook_path"
    };

    public static String extract(JsonNode input) {
        if (input == null || !input.isObject()) return null;
        for (String k : KEYS) {
            if (input.has(k) && input.get(k).isTextual()) {
                String p = input.get(k).asText("").trim();
                if (!p.isEmpty()) return p.replace('\\', '/');
            }
        }
        return null;
    }
}
