package com.mzfuture.entire.checkpoint.git;

import com.mzfuture.entire.gitsync.service.GitOperationService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/// Reads file content at a given revision and walks commits (for checkpoint sync).
@Slf4j
@Component
public class CheckpointGitReader {

    private final GitOperationService gitOperationService;

    public CheckpointGitReader(GitOperationService gitOperationService) {
        this.gitOperationService = gitOperationService;
    }

    /// Open repository with working directory, explicitly setGitDir(.git) + setWorkTree(repoDir) to prevent create(repoDir) from being treated as bare, which would cause resolve() to return null for packed-refs.
    private static Repository openRepository(File repoDir) throws IOException {
        File gitDir = new File(repoDir, ".git");
        if (gitDir.isDirectory()) {
            return new FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(repoDir).build();
        }
        return FileRepositoryBuilder.create(repoDir);
    }

    /// Read file content at the given revision (commit SHA or HEAD).
    /// @param repoId repository id
    /// @param revision commit SHA or "HEAD"
    /// @param path path relative to repo root, e.g. "1a/d322329de8/metadata.json"
    /// @return content as string, or empty if file not found
    public Optional<String> getFileContent(Long repoId, String revision, String path) {
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Optional.empty();
        }
        try (Repository repository = openRepository(repoDir)) {
            ObjectId revId = repository.resolve(revision);
            if (revId == null) {
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(revId);
                RevTree tree = commit.getTree();
                try (ObjectReader reader = repository.newObjectReader();
                     TreeWalk treeWalk = TreeWalk.forPath(reader, path, tree)) {
                    if (treeWalk == null) {
                        return Optional.empty();
                    }
                    ObjectId blobId = treeWalk.getObjectId(0);
                    ObjectLoader loader = reader.open(blobId);
                    byte[] bytes = loader.getBytes();
                    return Optional.of(new String(bytes, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.warn("Read file at revision failed: repoId={}, revision={}, path={}, error={}",
                    repoId, revision, path, e.getMessage());
            return Optional.empty();
        }
    }

    /// Resolve the first parent commit SHA. Empty for root commits.
    /// @return parent commit SHA, or empty for root commit / resolution error
    public Optional<String> getParentCommitSha(Long repoId, String commitSha) {
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Optional.empty();
        }
        try (Repository repository = openRepository(repoDir);
             RevWalk walk = new RevWalk(repository)) {
            ObjectId revId = repository.resolve(commitSha);
            if (revId == null) return Optional.empty();
            RevCommit commit = walk.parseCommit(revId);
            if (commit.getParentCount() == 0) return Optional.empty();
            return Optional.of(commit.getParent(0).getName());
        } catch (IOException e) {
            log.warn("Resolve parent failed: repoId={}, commit={}, error={}", repoId, commitSha, e.getMessage());
            return Optional.empty();
        }
    }

    /// Walk commits from the given branch ref (refs/heads/branchName) toward history; stop at stopAtCommitSha (exclusive) or root.
    /// @param repoId repository id
    /// @param branchName short branch name, e.g. "main", "entire/checkpoints/v1"
    /// @param stopAtCommitSha optional commit SHA to stop before (exclusive)
    /// @return list of commits (newest first)
    public List<CommitInfo> walkCommitsFromBranch(Long repoId, String branchName, String stopAtCommitSha) {
        List<CommitInfo> result = new ArrayList<>();
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return result;
        }
        String refName = branchName.startsWith("refs/heads/") ? branchName : "refs/heads/" + branchName;
        try (Repository repository = openRepository(repoDir);
             RevWalk walk = new RevWalk(repository)) {
            ObjectId branchHead = resolveBranchToCommit(repository, refName);
            if (branchHead == null) {
                return result;
            }
            walk.markStart(walk.parseCommit(branchHead));
            ObjectId stopId = (stopAtCommitSha != null && !stopAtCommitSha.isEmpty())
                    ? repository.resolve(stopAtCommitSha) : null;
            for (RevCommit c : walk) {
                if (stopId != null && c.getId().equals(stopId)) {
                    break;
                }
                String authorName = getAuthorName(c);
                long commitTimeMs = toCommitTimeMs(c);
                result.add(new CommitInfo(c.getName(), c.getFullMessage(), authorName, commitTimeMs));
            }
        } catch (IOException e) {
            log.warn("Walk commits from branch failed: repoId={}, branch={}, error={}", repoId, branchName, e.getMessage());
        }
        return result;
    }

    /// Convert RevCommit.getCommitTime() (seconds) to ms; fallback to current time if invalid.
    private static long toCommitTimeMs(RevCommit c) {
        int sec = c.getCommitTime();
        if (sec > 0) {
            return sec * 1000L;
        }
        return System.currentTimeMillis();
    }

    private static String getAuthorName(RevCommit c) {
        PersonIdent ident = c.getAuthorIdent();
        return ident != null ? ident.getName() : null;
    }

    /// List local branch short names (refs/heads/*).
    public List<String> listLocalBranchNames(Long repoId) {
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Collections.emptyList();
        }
        try (Repository repository = openRepository(repoDir)) {
            RefDatabase refDb = repository.getRefDatabase();
            List<Ref> refs = refDb.getRefsByPrefix("refs/heads");
            return refs.stream()
                    .map(ref -> Repository.shortenRefName(ref.getName()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("List local branches failed: repoId={}, error={}", repoId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /// Resolve a branch to commit SHA (for reading metadata from that branch). Uses refs/heads/branchName.
    public Optional<String> resolveBranchCommitSha(Long repoId, String branchName) {
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Optional.empty();
        }
        try (Repository repository = openRepository(repoDir)) {
            String refName = branchName.startsWith("refs/heads/") ? branchName : "refs/heads/" + branchName;
            ObjectId id = resolveBranchToCommit(repository, refName);
            return id != null ? Optional.of(id.getName()) : Optional.empty();
        } catch (IOException e) {
            log.warn("Resolve branch failed: repoId={}, branch={}, error={}", repoId, branchName, e.getMessage());
            return Optional.empty();
        }
    }

    /// Get line addition/deletion stats for a commit (diff against first parent).
    /// Root commit returns LineStats(0, 0). Merge commits use first parent only.
    /// @return LineStats with additions and deletions, or empty on error
    public Optional<LineStats> getCommitLineStats(Long repoId, String commitSha) {
        File repoDir = new File(gitOperationService.getLocalRepositoryPath(repoId));
        if (!repoDir.exists() || !new File(repoDir, ".git").exists()) {
            return Optional.empty();
        }
        try (Repository repository = openRepository(repoDir);
             ObjectReader reader = repository.newObjectReader()) {
            ObjectId revId = repository.resolve(commitSha);
            if (revId == null) {
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(revId);
                if (commit.getParentCount() == 0) {
                    return Optional.of(new LineStats(0, 0));
                }
                RevCommit parent = walk.parseCommit(commit.getParent(0));
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, parent.getTree());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree());

                try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    diffFormatter.setRepository(repository);
                    List<DiffEntry> entries = diffFormatter.scan(oldTree, newTree);
                    int additions = 0;
                    int deletions = 0;
                    DiffAlgorithm diffAlg = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);
                    for (DiffEntry entry : entries) {
                        ObjectId oldId = (entry.getChangeType() == DiffEntry.ChangeType.ADD)
                                ? ObjectId.zeroId()
                                : repository.resolve(entry.getOldId().name());
                        ObjectId newId = (entry.getChangeType() == DiffEntry.ChangeType.DELETE)
                                ? ObjectId.zeroId()
                                : repository.resolve(entry.getNewId().name());
                        if (oldId == null) oldId = ObjectId.zeroId();
                        if (newId == null) newId = ObjectId.zeroId();
                        RawText a = loadRawTextForDiff(reader, oldId, newId, true);
                        RawText b = loadRawTextForDiff(reader, oldId, newId, false);
                        EditList editList = diffAlg.diff(RawTextComparator.DEFAULT, a, b);
                        for (Edit edit : editList) {
                            deletions += edit.getEndA() - edit.getBeginA();
                            additions += edit.getEndB() - edit.getBeginB();
                        }
                    }
                    return Optional.of(new LineStats(additions, deletions));
                }
            }
        } catch (IOException e) {
            log.warn("Get commit line stats failed: repoId={}, commit={}, error={}", repoId, commitSha, e.getMessage());
            return Optional.empty();
        }
    }

    private static RawText loadRawTextForDiff(ObjectReader reader, AnyObjectId oldId, AnyObjectId newId, boolean wantOld) throws IOException {
        AnyObjectId loadId = wantOld ? oldId : newId;
        if (loadId == null || loadId.equals(ObjectId.zeroId())) {
            return new RawText(new byte[0]);
        }
        ObjectLoader loader = reader.open(loadId);
        return new RawText(loader.getBytes());
    }

    /// Resolve branch ref to commit ObjectId. Tries resolve(), then refresh+resolve(), then exactRef() for packed-refs.
    private static ObjectId resolveBranchToCommit(Repository repository, String refName) throws IOException {
        ObjectId id = repository.resolve(refName);
        if (id != null) {
            return id;
        }
        RefDatabase refDb = repository.getRefDatabase();
        refDb.refresh();
        id = repository.resolve(refName);
        if (id != null) {
            return id;
        }
        Ref ref = refDb.exactRef(refName);
        if (ref != null && ref.getObjectId() != null) {
            return ref.getObjectId();
        }
        return null;
    }

    /// Commit id (SHA), full message for trailer parsing, author display name, and commit time (Unix epoch ms).
    public record CommitInfo(String commitSha, String fullMessage, String authorName, long commitTime) {}

    /// Line addition/deletion stats for a commit (from git diff).
    public record LineStats(int additions, int deletions) {}
}
