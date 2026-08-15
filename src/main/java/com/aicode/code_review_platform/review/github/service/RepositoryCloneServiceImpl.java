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

@Service
@Slf4j
public class RepositoryCloneServiceImpl implements RepositoryCloneService {

    @Autowired
    private RepositoryConfig repositoryConfig;

    @Override
    public Path cloneRepository(String repositoryUrl, Long reviewId) {

        // Each review gets its own directory under the configured temp dir, keyed by review id,
        // so concurrent submissions of the same repository never write over each other.
        Path repositoryPath = Paths.get(repositoryConfig.getTempDirectory(), "review-" + reviewId);

        log.info("Cloning repository {} for review id {} into {}", repositoryUrl, reviewId, repositoryPath);

        try {
            Files.createDirectories(repositoryPath);

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
            log.error("Failed to clone repository {} for review id {}: {}", repositoryUrl, reviewId, e.getMessage(), e);

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
        if (path == null || !Files.exists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {

            // Reverse order puts children before their parents, which is what Files.delete needs -
            // it will not remove a directory that still has contents.
            walk.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);

            log.info("Deleted temporary repository {}", path);

        } catch (IOException e) {
            // Cleanup is best-effort: a leftover temp directory must not fail the submission.
            log.warn("Failed to delete temporary repository {}: {}", path, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {

        File file = path.toFile();

        // JGit marks packed object files read-only, which makes plain delete fail on Windows.
        if (!file.canWrite()) {
            file.setWritable(true);
        }

        try {
            Files.delete(path);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }
}
