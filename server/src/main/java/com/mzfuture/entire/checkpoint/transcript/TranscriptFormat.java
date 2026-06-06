package com.mzfuture.entire.checkpoint.transcript;

import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;

import java.util.List;

/// Strategy interface for a single transcript format. Implementations are Spring beans
/// injected into `TranscriptNormalizer` and tried in priority order.
public interface TranscriptFormat {

    /// Stable label used in `meta.sourceFormat`. Lowercase, kebab-case, suffixed with the
    /// structural form (e.g. `"claude-code-ndjson"`, `"opencode-json"`).
    String name();

    /// Higher = tried first. OpenCode at 100, Gemini at 90, ClaudeCode at 80, Cursor at 75,
    /// Droid at 70, Codex at 60, Copilot at 50, Pi at 40.
    int priority();

    /// Cheap O(1) or O(n)-with-early-stop probe. Must not throw; return false on parse errors.
    /// `raw` is already trimmed of leading/trailing whitespace and non-null.
    boolean matches(String raw);

    /// Full parse. May throw; the caller captures the exception into the warnings list and
    /// stops trying lower-priority formats (the matched format is the right agent).
    /// `warnings` is mutable; parsers may add diagnostic strings.
    NormalizedTranscriptDTO parse(String raw, long rawBytesLength, List<String> warnings)
            throws Exception;
}
