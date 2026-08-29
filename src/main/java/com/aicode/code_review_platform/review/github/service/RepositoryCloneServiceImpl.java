package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.RepositoryConfig;
import com.aicode.code_review_platform.review.github.exception.RepositoryCloneException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class RepositoryCloneServiceImpl implements RepositoryCloneService {

    /**
     * STEP 6.2 - every directory this service creates carries this prefix, and cleanup refuses to
     * touch anything that does not. It is the marker that says "this directory is ours to delete".
     */
    private static final String REPOSITORY_DIRECTORY_PREFIX = "review-";

    @Autowired
    private RepositoryConfig repositoryConfig;

    @Override
    public Path cloneRepository(String repositoryUrl, Long reviewId) {

        Path baseDirectory = baseDirectory();

        // Declared outside the try so a failure after the directory exists can still remove it.
        Path repositoryPath = null;

        log.info("Cloning repository {} for reviewId={}", repositoryUrl, reviewId);

        try {
            // The shared parent (/app/temp-repositories in the container, backed by the
            // repositories_data volume). Created once, and never deleted by cleanup.
            Files.createDirectories(baseDirectory);

            // review-<id>-<uuid>: the review id keeps the logs readable, the UUID keeps the
            // directory unique. Two concurrent reviews - including two deliveries of the same
            // review id - therefore never share a checkout. createDirectory (not
            // createDirectories) fails instead of reusing a directory that already exists.
            repositoryPath = baseDirectory.resolve(
                    REPOSITORY_DIRECTORY_PREFIX + reviewId + "-" + UUID.randomUUID()
            );
            Files.createDirectory(repositoryPath);

            log.info("Created temporary repository directory {} for reviewId={}", repositoryPath, reviewId);

            // Full JGit clone, then close immediately - we only ever read the working tree as plain
            // files, so the Git handle (and its file locks) is not needed afterwards.
            Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(repositoryPath.toFile())
                    .call()
                    .close();

            log.info("Cloned repository {} to {}", repositoryUrl, repositoryPath);
            return repositoryPath;

        } catch (IOException | GitAPIException e) {
            log.error("Failed to clone repository {} for reviewId={}: {}", repositoryUrl, reviewId, e.getMessage(), e);

            // Clean up the partial checkout ourselves: the caller never received a path, so its
            // own finally block has nothing to delete.
            deleteRepository(repositoryPath);

            throw new RepositoryCloneException("Failed to clone repository: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void deleteRepository(Path path) {

        // Called from a finally block, so it has to tolerate a clone that never happened.
        if (path == null) {
            log.debug("Repository cleanup skipped: no repository directory was created");
            return;
        }

        Path repositoryPath = path.toAbsolutePath().normalize();

        // STEP 6.2 - the safety gate. Everything past this point deletes recursively, so a path
        // that is not one of our own per-review directories must never get through.
        if (!isManagedRepositoryDirectory(repositoryPath)) {
            log.error(
                    "Repository cleanup refused for {}: not a per-review directory directly under {}",
                    repositoryPath,
                    baseDirectory()
            );
            return;
        }

        if (!Files.exists(repositoryPath)) {
            log.debug("Repository cleanup skipped for {}: directory no longer exists", repositoryPath);
            return;
        }

        log.info("Repository cleanup started for {}", repositoryPath);

        try (var walk = Files.walk(repositoryPath)) {

            // Reverse order puts children before their parents, which is what Files.delete needs -
            // it will not remove a directory that still has contents. The repository directory
            // itself sorts last, so it goes only once everything inside it is gone.
            List<Path> failures = walk.sorted(Comparator.reverseOrder())
                    .filter(entry -> !deleteQuietly(entry))
                    .toList();

            if (failures.isEmpty()) {
                log.info("Repository cleanup completed for {}", repositoryPath);
            } else {
                // Not rethrown: a leftover temp directory is a disk problem, not a review failure.
                log.error(
                        "Repository cleanup incomplete for {}: {} entry/entries could not be deleted, first was {}",
                        repositoryPath,
                        failures.size(),
                        failures.getFirst()
                );
            }

        } catch (IOException | RuntimeException e) {
            // Cleanup is best-effort and must never change the outcome of the review that
            // triggered it, so the failure is logged in full instead of propagated.
            log.error("Repository cleanup failed for {}: {}", repositoryPath, e.getMessage(), e);
        }
    }

    /**
     * STEP 6.2 - a path is deletable only when it is a direct child of the configured temp
     * directory and carries the prefix this service clones into. That rules out the parent itself
     * (/app/temp-repositories), anything above it (/app, /), anything outside it entirely, nested
     * paths inside a checkout, and any unrelated directory that happens to sit next to ours.
     */
    private boolean isManagedRepositoryDirectory(Path candidate) {

        Path baseDirectory = baseDirectory();

        // A symlink dropped into the temp directory could otherwise point cleanup at an unrelated
        // tree. Files.walk does not follow links, but the top-level entry is checked explicitly so
        // it is never even entered.
        if (Files.isSymbolicLink(candidate)) {
            return false;
        }

        return baseDirectory.equals(candidate.getParent())
                && candidate.getFileName().toString().startsWith(REPOSITORY_DIRECTORY_PREFIX);
    }

    /** The configured shared parent directory, absolute and normalized. Never deleted. */
    private Path baseDirectory() {
        return Paths.get(repositoryConfig.getTempDirectory()).toAbsolutePath().normalize();
    }

    /** Deletes a single entry, reporting whether it is gone. Never throws. */
    private boolean deleteQuietly(Path path) {

        File file = path.toFile();

        // JGit marks packed object files read-only, which makes plain delete fail on Windows.
        if (!file.canWrite()) {
            file.setWritable(true);
        }

        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
            return false;
        }
    }
}
