package com.aicode.code_review_platform.review.github.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface RepositoryScannerService {

    /**
     * PHASE 3 - walks the cloned tree and returns the paths of reviewable source files, skipping
     * build output, VCS internals and dependency directories. File contents are not read here.
     */
    List<Path> scanRepository(Path repositoryPath) throws IOException;

}
