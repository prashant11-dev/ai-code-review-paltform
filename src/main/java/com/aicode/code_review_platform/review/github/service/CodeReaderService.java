package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.dto.CodeFile;

import java.nio.file.Path;
import java.util.List;

public interface CodeReaderService {

    /**
     * PHASE 4 - reads the scanned paths into memory, tagging each with its repo-relative path and
     * language. Files that cannot be read are skipped, so the result may be shorter than the input.
     */
    List<CodeFile> readFiles(
            List<Path> paths,
            Path repositoryRoot
    );

}
