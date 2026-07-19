package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.dto.CodeFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CodeReaderServiceImpl implements CodeReaderService {

    private static final Map<String, String> EXTENSION_LANGUAGE_MAP =
            Map.of(
                    ".java", "JAVA",
                    ".js", "JAVASCRIPT",
                    ".jsx", "JAVASCRIPT",
                    ".ts", "TYPESCRIPT",
                    ".tsx", "TYPESCRIPT",
                    ".py", "PYTHON"
            );

    @Override
    public List<CodeFile> readFiles(List<Path> paths, Path repositoryRoot) {

        List<CodeFile> codeFiles = new ArrayList<>();

        for (Path path : paths) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);

                codeFiles.add(
                        CodeFile.builder()
                                .fileName(path.getFileName().toString())
                                .relativePath(toRelativePath(repositoryRoot, path))
                                .language(resolveLanguage(path))
                                .content(content)
                                .build()
                );
            } catch (IOException e) {
                log.warn("Failed to read file {}, skipping", path, e);
            }
        }

        return codeFiles;
    }

    private String toRelativePath(Path repositoryRoot, Path path) {
        return repositoryRoot.relativize(path)
                .toString()
                .replace('\\', '/');
    }

    private String resolveLanguage(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return "UNKNOWN";
        }
        return EXTENSION_LANGUAGE_MAP.getOrDefault(fileName.substring(dotIndex), "UNKNOWN");
    }
}
