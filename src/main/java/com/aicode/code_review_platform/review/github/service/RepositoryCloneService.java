package com.aicode.code_review_platform.review.github.service;

import java.nio.file.Path;

public interface RepositoryCloneService {

    /**
     * PHASE 2 - clones the repository into a per-review temp directory and returns its root.
     * The only network-bound phase of the pipeline.
     */
    Path cloneRepository(String repositoryUrl, Long reviewId);

    /**
     * PHASE 9 - deletes the temp checkout once the chunks are safely in the database. Best-effort
     * and null-safe, because it runs from a finally block that may execute after a failed clone.
     */
    void deleteRepository(Path path);


}
