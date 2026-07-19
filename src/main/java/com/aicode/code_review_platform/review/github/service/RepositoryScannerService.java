package com.aicode.code_review_platform.review.github.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface RepositoryScannerService {

    List<Path> scanRepository(Path repositoryPath) throws IOException;

}
