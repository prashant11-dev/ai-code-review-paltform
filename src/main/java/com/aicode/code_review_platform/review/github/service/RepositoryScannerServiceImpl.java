package com.aicode.code_review_platform.review.github.service;

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

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(".git", "target", "build", "node_modules", "dist", ".idea", ".vscode");

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".js", ".ts", ".jsx", ".tsx", ".py");

    @Override
    public List<Path> scanRepository(Path repositoryPath) throws IOException {
        try (Stream<Path> paths = Files.walk(repositoryPath)) {
            return paths.filter(Files::isRegularFile).filter(path -> isNotInIgnoredDirectory(repositoryPath, path)).filter(this::hasSupportedExtension).collect(Collectors.toList());
        }
    }

    private boolean isNotInIgnoredDirectory(Path repositoryPath, Path path) {
        Path relative = repositoryPath.relativize(path);
        for (Path segment : relative) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSupportedExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(fileName.substring(dotIndex));
    }
}
