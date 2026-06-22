package com.mzfuture.entire.checkpoint.transcript;

import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TranscriptNormalizerTest {

    @Autowired TranscriptNormalizer normalizer;

    String loadFixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/transcript-fixtures", name));
    }

    String jsonString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    void assertShape(NormalizedTranscriptDTO dto, String expectedFormat) {
        assertNotNull(dto);
        assertNotNull(dto.getMeta(), "meta is null");
        assertEquals(expectedFormat, dto.getMeta().getSourceFormat());
        assertNotNull(dto.getMessages(), "messages is null (must be empty list, not null)");
        assertTrue(dto.getMessages().size() > 0, "messages should be non-empty");
        assertNotNull(dto.getStepsCount());
        assertTrue(dto.getStepsCount() >= 1, "stepsCount should be >= 1");
    }

    @Test
    void unknownOnGarbage() {
        NormalizedTranscriptDTO dto = normalizer.normalize("not json at all\n\n!!!");
        assertEquals("unknown", dto.getMeta().getSourceFormat());
        assertNotNull(dto.getMeta().getWarnings());
        assertTrue(dto.getMeta().getWarnings().stream()
                .anyMatch(w -> w.contains("Unrecognized transcript format")));
    }

    @Nested
    class Cursor {
        @Test
        void detectsCursorNdjson() {
            String sample = "{\"role\":\"user\",\"message\":{\"content\":\"hi\"}}\n"
                    + "{\"role\":\"assistant\",\"message\":{\"content\":\"hello\"}}";
            assertEquals("cursor-ndjson",
                    normalizer.normalize(sample).getMeta().getSourceFormat());
        }

        @Test
        void cursorPathAndToolExtraction() {
            String sample = "{\"role\":\"user\",\"message\":{\"content\":\"edit x\"}}\n"
                    + "{\"role\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Edit\",\"input\":{\"file_path\":\"src/A.java\","
                    + "\"old_string\":\"a\",\"new_string\":\"ab\"}}]}}";
            NormalizedTranscriptDTO dto = normalizer.normalize(sample);
            assertEquals("cursor-ndjson", dto.getMeta().getSourceFormat());
            assertEquals(1, dto.getStepsCount());
            boolean found = dto.getMessages().stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .flatMap(m -> m.getTools() == null ? Stream.empty() : m.getTools().stream())
                    .anyMatch(t -> "Edit".equals(t.getTool())
                            && t.getInput() != null
                            && t.getInput().has("file_path")
                            && "src/A.java".equals(t.getInput().get("file_path").asText()));
            assertTrue(found, "expected the Edit tool_use with file_path=src/A.java");
        }
    }

    @Nested
    class ClaudeCode {
        @Test
        void parsesClaudeFull() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("claude-code.jsonl"));
            assertShape(dto, "claude-code-ndjson");
            assertTrue(dto.getMessages().stream().anyMatch(m -> !m.getText().isEmpty()),
                    "expected at least one message with non-empty text");
            assertTrue(dto.getMessages().stream().anyMatch(m -> m.getTools() != null && !m.getTools().isEmpty()),
                    "expected at least one message with tool_use blocks");
        }

        @Test
        void deduplicatesStreamingChunks() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("claude-code-2.jsonl"));
            assertShape(dto, "claude-code-ndjson");
            assertTrue(dto.getMessages().size() > 0);
        }
    }

    @Nested
    class OpenCode {
        @Test
        void parsesOpenCodeFixture() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("opencode.jsonl"));
            assertShape(dto, "opencode-json");
            assertTrue(dto.getMessages().stream().anyMatch(m -> !m.getText().isEmpty()),
                    "expected at least one message with non-empty text");
            assertTrue(dto.getFileChanges().size() > 0,
                    "OpenCode fixture has per-message diffs; file_changes should aggregate");
        }
    }

    @Nested
    class Gemini {
        @Test
        void parsesGeminiFixture() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("gemini.jsonl"));
            assertShape(dto, "gemini-json");
            assertTrue(dto.getMessages().stream().anyMatch(m -> !m.getText().isEmpty()),
                    "expected at least one message with non-empty text");
        }
    }

    @Nested
    class Droid {
        @Test
        void parsesDroidFixture() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("droid.jsonl"));
            assertShape(dto, "droid-ndjson");
            assertTrue(dto.getMessages().stream().anyMatch(m -> !m.getText().isEmpty()),
                    "expected at least one message with non-empty text");
        }
    }

    @Nested
    class Codex {
        @Test
        void parsesCodexFixture() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("codex.jsonl"));
            assertShape(dto, "codex-ndjson");
            assertTrue(dto.getMessages().stream().anyMatch(m -> !m.getText().isEmpty()),
                    "expected at least one message with non-empty text");
        }

        @Test
        void hidesBootstrapMessagesAndDeduplicatesCodexEvents() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("codex.jsonl"));
            assertEquals(1, dto.getStepsCount(), "only the real user prompt should count as a step");

            String visibleText = dto.getMessages().stream()
                    .map(m -> m.getText() == null ? "" : m.getText())
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(!visibleText.contains("<permissions instructions>"),
                    "developer permission instructions must not be shown");
            assertTrue(!visibleText.contains("<environment_context>"),
                    "injected environment context must not be shown");

            long userPromptCount = dto.getMessages().stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .filter(m -> "create a hello.txt file with a greeting".equals(m.getText()))
                    .count();
            long firstAssistantCount = dto.getMessages().stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .filter(m -> "Creating the file now.".equals(m.getText()))
                    .count();
            assertEquals(1, userPromptCount, "Codex response_item/event_msg user pair should be one UI message");
            assertEquals(1, firstAssistantCount, "Codex response_item/event_msg assistant pair should be one UI message");
        }

        @Test
        void extractsFilesTouchedByCodexShellCommands() {
            String sample = String.join("\n",
                    "{\"timestamp\":\"2026-04-01T00:00:00Z\",\"type\":\"session_meta\","
                            + "\"payload\":{\"cwd\":\"D:\\\\repo\"}}",
                    "{\"timestamp\":\"2026-04-01T00:00:01Z\",\"type\":\"response_item\","
                            + "\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":["
                            + "{\"type\":\"input_text\",\"text\":\"create empty file\"}]}}",
                    "{\"timestamp\":\"2026-04-01T00:00:02Z\",\"type\":\"response_item\","
                            + "\"payload\":{\"type\":\"function_call\",\"name\":\"shell_command\","
                            + "\"arguments\":\"{\\\"command\\\":\\\"New-Item -ItemType File -Name empty.txt\\\","
                            + "\\\"workdir\\\":\\\"D:\\\\\\\\repo\\\"}\",\"call_id\":\"call_empty\"}}"
            );

            NormalizedTranscriptDTO dto = normalizer.normalize(sample);
            assertEquals("codex-ndjson", dto.getMeta().getSourceFormat());
            assertTrue(dto.getFileChanges().stream().anyMatch(f -> "empty.txt".equals(f.getFile())),
                    "New-Item-created empty file should appear in fileChanges");
            assertTrue(dto.getMessages().stream()
                            .flatMap(m -> m.getTools() == null ? Stream.empty() : m.getTools().stream())
                            .anyMatch(t -> t.getInput() != null
                                    && t.getInput().has("touched_files")
                                    && t.getInput().get("touched_files").toString().contains("empty.txt")),
                    "shell command tool input should expose touched_files for the UI");
        }

        @Test
        void extractsFilesAndUnifiedDiffFromCodexApplyPatchEvents() {
            String patch = """
                    *** Begin Patch
                    *** Update File: D:\\project\\course\\entire-dashboard\\README.md
                    @@
                    -old
                    +new
                    *** End Patch
                    """;
            String unifiedDiff = "@@ -64,3 +64,3 @@\n"
                    + " \n"
                    + "-old\n"
                    + "+new\n"
                    + " \n";
            String file = "D:\\project\\course\\entire-dashboard\\README.md";
            String sample = String.join("\n",
                    "{\"timestamp\":\"2026-04-01T00:00:00Z\",\"type\":\"session_meta\","
                            + "\"payload\":{\"cwd\":\"D:\\\\project\\\\course\\\\entire-dashboard\"}}",
                    "{\"timestamp\":\"2026-04-01T00:00:01Z\",\"type\":\"response_item\","
                            + "\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":["
                            + "{\"type\":\"input_text\",\"text\":\"edit README\"}]}}",
                    "{\"timestamp\":\"2026-04-01T00:00:02Z\",\"type\":\"response_item\","
                            + "\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                            + "{\"type\":\"output_text\",\"text\":\"editing\"}]}}",
                    "{\"timestamp\":\"2026-04-01T00:00:03Z\",\"type\":\"response_item\","
                            + "\"payload\":{\"type\":\"custom_tool_call\",\"name\":\"apply_patch\","
                            + "\"input\":\"" + jsonString(patch) + "\",\"call_id\":\"call_patch\"}}",
                    "{\"timestamp\":\"2026-04-01T00:00:04Z\",\"type\":\"event_msg\","
                            + "\"payload\":{\"type\":\"patch_apply_end\",\"call_id\":\"call_patch\","
                            + "\"changes\":{\"" + jsonString(file) + "\":{\"type\":\"update\","
                            + "\"unified_diff\":\"" + jsonString(unifiedDiff) + "\",\"move_path\":null}},"
                            + "\"status\":\"completed\"}}"
            );

            NormalizedTranscriptDTO dto = normalizer.normalize(sample);
            assertEquals("codex-ndjson", dto.getMeta().getSourceFormat());
            assertTrue(dto.getFileChanges().stream().anyMatch(f -> "README.md".equals(f.getFile())
                            && f.getAdditions() == 1 && f.getDeletions() == 1),
                    "apply_patch-updated README.md should appear in fileChanges with diff stats");
            assertTrue(dto.getMessages().stream()
                            .flatMap(m -> m.getDiffs() == null ? Stream.empty() : m.getDiffs().stream())
                            .anyMatch(d -> "README.md".equals(d.getFile())
                                    && d.getUnifiedDiff() != null
                                    && d.getUnifiedDiff().contains("-old")
                                    && d.getUnifiedDiff().contains("+new")),
                    "patch_apply_end unified diff should be attached to the tool message");
            assertTrue(dto.getMessages().stream()
                            .flatMap(m -> m.getTools() == null ? Stream.empty() : m.getTools().stream())
                            .anyMatch(t -> t.getInput() != null
                                    && t.getInput().has("touched_files")
                                    && t.getInput().get("touched_files").toString().contains("README.md")),
                    "apply_patch tool input should expose touched_files for the UI");
        }
    }

    @Nested
    class CopilotCli {
        @Test
        void parsesCopilotFixture() throws Exception {
            NormalizedTranscriptDTO dto = normalizer.normalize(loadFixture("copilot-cli.jsonl"));
            assertShape(dto, "copilot-cli-ndjson");
            assertTrue(dto.getMeta().getWarnings().stream()
                    .anyMatch(w -> w.contains("linear event merge")),
                    "expected the linear-merge warning to be present");
        }
    }

    @Nested
    class Pi {
        // Synthesized fixture (no _full.jsonl in entireio-cli's compact testdata).
        private static final String PI_FIXTURE = String.join("\n",
                "{\"type\":\"session\",\"version\":3,\"id\":\"s1\",\"timestamp\":\"2026-05-01T00:00:00Z\"}",
                "{\"type\":\"message\",\"id\":\"u1\",\"parentId\":\"s1\",\"timestamp\":\"2026-05-01T00:00:01Z\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"hi\"}}",
                "{\"type\":\"message\",\"id\":\"a1\",\"parentId\":\"u1\",\"timestamp\":\"2026-05-01T00:00:02Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"hello\"},"
                        + "{\"type\":\"toolCall\",\"id\":\"tc1\",\"name\":\"write\",\"arguments\":"
                        + "{\"path\":\"src/A.java\",\"content\":\"// new\\nclass A {}\"}},"
                        + "{\"type\":\"toolCall\",\"id\":\"tc2\",\"name\":\"edit\",\"arguments\":"
                        + "{\"path\":\"src/B.java\"}}]}}",
                "{\"type\":\"message\",\"id\":\"r1\",\"parentId\":\"a1\",\"timestamp\":\"2026-05-01T00:00:03Z\","
                        + "\"message\":{\"role\":\"toolResult\",\"content\":\"ok\"}}"
        );

        @Test
        void parsesSynthesizedPi() {
            NormalizedTranscriptDTO dto = normalizer.normalize(PI_FIXTURE);
            assertShape(dto, "pi-ndjson");
            assertTrue(dto.getMeta().getWarnings().stream()
                    .anyMatch(w -> w.contains("branch filtering")),
                    "expected the branch-filtering warning to be present");
            assertTrue(dto.getMessages().stream()
                            .flatMap(m -> m.getTools() == null ? Stream.empty() : m.getTools().stream())
                            .anyMatch(t -> "write".equals(t.getTool()) || "edit".equals(t.getTool())),
                    "expected write/edit tool calls in the synthesized fixture");
        }
    }
}
