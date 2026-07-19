package com.aicode.code_review_platform.review.github.service;

import java.nio.file.Path;

public interface RepositoryCloneService {

    Path cloneRepository(String repositoryUrl, Long reviewId);

    void deleteRepository(Path path);


}
