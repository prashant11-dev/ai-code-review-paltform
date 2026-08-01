package com.aicode.code_review_platform.review.github.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class RepositoryScannerServiceImpl implements RepositoryScannerService {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryScannerServiceImpl.class);

    // Generated, vendored and IDE folders: reviewing them wastes AI budget and produces noise.
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", "target", "build", "node_modules", "dist", ".idea", ".vscode");

    // The languages the review prompt can currently reason about. Anything else is skipped.
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".js", ".ts", ".jsx", ".tsx", ".py");

    /**
     * PHASE 3 - narrows the cloned tree down to the files actually worth reviewing.
     */
    @Override
    public List<Path> scanRepository(Path repositoryPath) throws IOException {
        logger.info("Scanning repository at {} for supported source files", repositoryPath);

        try (Stream<Path> paths = Files.walk(repositoryPath)) {
            // Three filters in order: real files only (no directories/symlink targets), not inside
            // an ignored folder, and a reviewable extension. Contents are never opened here.
            List<Path> matches = paths.filter(Files::isRegularFile).filter(path -> isNotInIgnoredDirectory(repositoryPath, path)).filter(this::hasSupportedExtension).collect(Collectors.toList());

            logger.info("Repository scan at {} found {} supported file(s)", repositoryPath, matches.size());
            return matches;
        } catch (IOException e) {
            // Unlike a single unreadable file, a failed walk means we cannot trust the file list at
            // all, so rethrow and let the orchestrator fail the review.
            logger.error("Failed to scan repository at {}: {}", repositoryPath, e.getMessage(), e);
            throw e;
        }
    }

    private boolean isNotInIgnoredDirectory(Path repositoryPath, Path path) {
        // Checks every segment of the path, not just the parent, so a nested node_modules or a
        // target directory buried in a submodule is excluded too.
        Path relative = repositoryPath.relativize(path);
        for (Path segment : relative) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSupportedExtension(Path path) {
        // Extension is taken from the last dot, so files with no dot (LICENSE, Makefile) drop out.
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(fileName.substring(dotIndex));
    }
}
