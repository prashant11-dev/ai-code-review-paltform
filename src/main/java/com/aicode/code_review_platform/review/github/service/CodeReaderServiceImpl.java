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

    /**
     * PHASE 4 - loads the scanned paths into memory as prompt-ready CodeFile objects.
     */
    @Override
    public List<CodeFile> readFiles(List<Path> paths, Path repositoryRoot) {

        log.info("Reading {} candidate file(s) from {}", paths.size(), repositoryRoot);

        List<CodeFile> codeFiles = new ArrayList<>();

        for (Path path : paths) {
            try {
                // Everything is read as UTF-8; a binary or differently-encoded file lands in the
                // catch below and is dropped rather than corrupting the prompt.
                String content = Files.readString(path, StandardCharsets.UTF_8);

                // Carries the relative path and language alongside the content, because the AI
                // prompt needs that context to report findings against real file locations.
                codeFiles.add(
                        CodeFile.builder()
                                .fileName(path.getFileName().toString())
                                .relativePath(toRelativePath(repositoryRoot, path))
                                .language(resolveLanguage(path))
                                .content(content)
                                .build()
                );
            } catch (IOException e) {
                // One unreadable file must not sink the whole review - skip it and keep going.
                log.warn("Failed to read file {}, skipping", path, e);
            }
        }

        log.info("Read {} of {} candidate file(s) from {}", codeFiles.size(), paths.size(), repositoryRoot);

        return codeFiles;
    }

    private String toRelativePath(Path repositoryRoot, Path path) {
        // Strips the temp-clone prefix and normalises to forward slashes, so paths shown in the
        // review match what the user sees on GitHub even when we cloned on Windows.
        return repositoryRoot.relativize(path)
                .toString()
                .replace('\\', '/');
    }

    private String resolveLanguage(Path path) {
        // Extension-based language tag for the prompt. Unmapped files still get reviewed, just
        // without a language hint.
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return "UNKNOWN";
        }
        return EXTENSION_LANGUAGE_MAP.getOrDefault(fileName.substring(dotIndex), "UNKNOWN");
    }
}
