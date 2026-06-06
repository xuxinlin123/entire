package com.mzfuture.entire.checkpoint.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptMetaDTO;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/// Parses raw `full.jsonl` / transcript text into the stable `NormalizedTranscriptDTO` used by
/// the admin UI. Uses a strategy chain of injected `TranscriptFormat` beans, tried in
/// priority order. Never throws; on total failure returns empty messages + `unknown` format.
@Component
public class TranscriptNormalizer {

    public static final int SCHEMA_VERSION = 1;
    public static final String FORMAT_UNKNOWN = "unknown";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<TranscriptFormat> injectedFormats;
    private List<TranscriptFormat> formats; // sorted by priority desc

    public TranscriptNormalizer(List<TranscriptFormat> formats) {
        this.injectedFormats = formats;
    }

    @PostConstruct
    void sortFormats() {
        this.formats = new ArrayList<>(injectedFormats);
        this.formats.sort(Comparator.comparingInt(TranscriptFormat::priority).reversed());
    }

    /// Normalize transcript text. Never throws.
    public NormalizedTranscriptDTO normalize(String raw) {
        List<String> warnings = new ArrayList<>();
        if (raw == null) {
            return empty(lenBytes(""), warnings, FORMAT_UNKNOWN);
        }
        long rawBytesLength = lenBytes(raw);
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            warnings.add("Empty transcript");
            return empty(rawBytesLength, warnings, FORMAT_UNKNOWN);
        }

        for (TranscriptFormat f : formats) {
            if (f.matches(trimmed)) {
                try {
                    NormalizedTranscriptDTO dto = f.parse(trimmed, rawBytesLength, warnings);
                    if (dto != null) {
                        return dto;
                    }
                } catch (Exception e) {
                    warnings.add(f.name() + " parse error: " + e.getMessage());
                }
                // Matched but parse failed: stop here. The matched format is the right
                // agent; the data is just malformed.
                break;
            }
        }

        warnings.add("Unrecognized transcript format; no messages extracted");
        return empty(rawBytesLength, warnings, FORMAT_UNKNOWN);
    }

    private static long lenBytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8).length;
    }

    private NormalizedTranscriptDTO empty(long rawBytesLength, List<String> warnings, String format) {
        NormalizedTranscriptDTO dto = new NormalizedTranscriptDTO();
        dto.setSchemaVersion(SCHEMA_VERSION);
        dto.setMeta(meta(format, rawBytesLength, warnings));
        dto.setStepsCount(0);
        dto.setFileChanges(Collections.emptyList());
        dto.setMessages(Collections.emptyList());
        return dto;
    }

    private NormalizedTranscriptMetaDTO meta(String sourceFormat, long rawBytesLength, List<String> warnings) {
        NormalizedTranscriptMetaDTO m = new NormalizedTranscriptMetaDTO();
        m.setSourceFormat(sourceFormat);
        m.setRawBytesLength(rawBytesLength);
        if (!warnings.isEmpty()) {
            m.setWarnings(new ArrayList<>(warnings));
        }
        return m;
    }
}
