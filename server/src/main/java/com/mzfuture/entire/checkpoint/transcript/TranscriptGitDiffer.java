package com.mzfuture.entire.checkpoint.transcript;

import com.mzfuture.entire.checkpoint.dto.response.NormalizedTranscriptDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileChangeSummaryDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptFileDiffDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptMessageViewDTO;
import com.mzfuture.entire.checkpoint.dto.response.TranscriptToolUseDTO;
import com.mzfuture.entire.checkpoint.git.CheckpointGitReader;
import com.mzfuture.entire.checkpoint.transcript.support.FilePathExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Enriches a `NormalizedTranscriptDTO` with real `before` / `after` file content pulled
/// from git. Required because most agent transcripts (Claude Code, Cursor, Droid, ...)
/// only carry edit fragments in tool_use, never whole-file snapshots — the UI diff view
/// needs the actual file content from the checkpoint commit and its parent to render
/// a meaningful diff.
///
/// Idempotent: if any message already has a `diffs` entry for a file (e.g. OpenCode,
/// which embeds per-message diffs in the transcript), that file is left untouched.
@Slf4j
@Component
public class TranscriptGitDiffer {

    private final CheckpointGitReader gitReader;

    public TranscriptGitDiffer(CheckpointGitReader gitReader) {
        this.gitReader = gitReader;
    }

    public void enrich(NormalizedTranscriptDTO dto, Long repoId, String commitSha) {
        if (dto == null || repoId == null || commitSha == null || commitSha.isEmpty()) return;
        List<TranscriptFileChangeSummaryDTO> fileChanges = dto.getFileChanges();
        List<TranscriptMessageViewDTO> messages = dto.getMessages();
        if (fileChanges == null || fileChanges.isEmpty() || messages == null || messages.isEmpty()) return;

        String parentSha = gitReader.getParentCommitSha(repoId, commitSha).orElse(null);
        String projectRoot = dto.getProjectRoot();

        for (TranscriptFileChangeSummaryDTO fc : fileChanges) {
            String treePath = fc.getFile();
            if (treePath == null || treePath.isEmpty()) continue;
            if (anyMessageHasDiff(messages, treePath)) continue;

            String gitRelPath = resolveGitPath(messages, treePath, projectRoot);
            if (gitRelPath == null) continue;

            Optional<String> afterOpt = gitReader.getFileContent(repoId, commitSha, gitRelPath);
            if (afterOpt.isEmpty()) {
                log.debug("Skipping diff for {}: not present in commit {}", treePath, commitSha);
                continue;
            }
            String before = parentSha != null
                    ? gitReader.getFileContent(repoId, parentSha, gitRelPath).orElse("")
                    : "";

            TranscriptFileDiffDTO diff = new TranscriptFileDiffDTO();
            diff.setFile(treePath);
            diff.setBefore(before);
            diff.setAfter(afterOpt.get());
            diff.setAdditions(fc.getAdditions() != null ? fc.getAdditions() : 0);
            diff.setDeletions(fc.getDeletions() != null ? fc.getDeletions() : 0);

            attachToLastMessage(messages, treePath, projectRoot, diff);
        }
    }

    private static boolean anyMessageHasDiff(List<TranscriptMessageViewDTO> messages, String file) {
        for (TranscriptMessageViewDTO m : messages) {
            List<TranscriptFileDiffDTO> diffs = m.getDiffs();
            if (diffs == null) continue;
            for (TranscriptFileDiffDTO d : diffs) {
                if (file.equals(d.getFile())) return true;
            }
        }
        return false;
    }

    /// Walk every tool_use to find one whose raw file path translates to the given tree
    /// path. The discovered raw path is then stripped of the agent's working directory
    /// to produce a path relative to the git root.
    private static String resolveGitPath(List<TranscriptMessageViewDTO> messages,
                                         String treePath, String projectRoot) {
        for (TranscriptMessageViewDTO m : messages) {
            if (m.getTools() == null) continue;
            for (TranscriptToolUseDTO t : m.getTools()) {
                String raw = FilePathExtractor.extract(t.getInput());
                if (raw != null && rawPathMatchesTree(raw, treePath, projectRoot)) {
                    return toGitRelative(raw, projectRoot, treePath);
                }
            }
        }
        // No tool's raw path resolved to this tree path. Best effort: assume the tree
        // path itself is git-relative (true for OpenCode; true for any format when the
        // agent's cwd equals the git root).
        return treePath;
    }

    private static boolean rawPathMatchesTree(String raw, String tree, String projectRoot) {
        if (raw.equals(tree)) return true;
        if (raw.endsWith("/" + tree)) return true;
        return stripProjectRoot(raw, projectRoot).equals(tree);
    }

    /// If the agent's working dir is the git root, dropping that prefix from the raw
    /// tool path yields the git-relative path. If the agent's cwd is a subdirectory of
    /// the git root, we'd need to add the subdir prefix back — we don't know that
    /// relationship, so fall back to the tree path (which works whenever cwd == root).
    private static String toGitRelative(String raw, String projectRoot, String treePath) {
        if (projectRoot != null && !projectRoot.isEmpty()) {
            String stripped = stripProjectRoot(raw, projectRoot);
            if (!stripped.equals(raw)) return stripped;
        }
        return treePath;
    }

    private static String stripProjectRoot(String filePath, String projectRoot) {
        if (projectRoot == null || projectRoot.isEmpty()) return filePath;
        String normalized = filePath.replace('\\', '/');
        String root = projectRoot.replace('\\', '/').replaceAll("/$", "");
        if (normalized.startsWith(root + "/")) {
            return normalized.substring(root.length() + 1);
        }
        return filePath;
    }

    /// Find the latest message whose tools touched the tree path and append the diff to
    /// its `diffs` list. Mirrors the lookup the UI does, so the per-message diffs[]
    /// contract is satisfied.
    private static void attachToLastMessage(List<TranscriptMessageViewDTO> messages,
                                            String treePath, String projectRoot,
                                            TranscriptFileDiffDTO diff) {
        Map<Integer, TranscriptMessageViewDTO> byIndex = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            TranscriptMessageViewDTO m = messages.get(i);
            if (m.getTools() == null) continue;
            for (TranscriptToolUseDTO t : m.getTools()) {
                String raw = FilePathExtractor.extract(t.getInput());
                if (raw != null && rawPathMatchesTree(raw, treePath, projectRoot)) {
                    byIndex.put(i, m);
                    break;
                }
            }
        }
        if (byIndex.isEmpty()) return;
        int lastIdx = -1;
        for (int idx : byIndex.keySet()) {
            if (idx > lastIdx) lastIdx = idx;
        }
        TranscriptMessageViewDTO target = byIndex.get(lastIdx);
        if (target.getDiffs() == null) target.setDiffs(new ArrayList<>());
        target.getDiffs().add(diff);
    }
}
