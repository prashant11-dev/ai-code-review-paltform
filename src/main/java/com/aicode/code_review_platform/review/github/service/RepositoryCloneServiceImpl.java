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

        Path repositoryPath = Paths.get(repositoryConfig.getTempDirectory(), "review-" + reviewId);

        try {
            Files.createDirectories(repositoryPath);

            Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(repositoryPath.toFile())
                    .call()
                    .close();

            log.info("Cloned repository {} to {}", repositoryUrl, repositoryPath);
            return repositoryPath;

        } catch (IOException | GitAPIException e) {
            throw new RepositoryCloneException("Failed to clone repository",
                    e);
        }
    }

    @Override
    public void deleteRepository(Path path) {

        if (path == null || !Files.exists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {

            walk.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);

            log.info("Deleted temporary repository {}", path);

        } catch (IOException e) {
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
