package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.dto.CodeFile;

import java.nio.file.Path;
import java.util.List;

public interface CodeReaderService {

    List<CodeFile> readFiles(
            List<Path> paths,
            Path repositoryRoot
    );

}
