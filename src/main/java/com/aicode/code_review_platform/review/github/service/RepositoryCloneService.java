package com.aicode.code_review_platform.review.github.service;

import java.nio.file.Path;

public interface RepositoryCloneService {

    /**
     * PHASE 2 - clones the repository into a per-review temp directory and returns its root.
     * The only network-bound phase of the pipeline.
     *
     * <p>STEP 6.2 - the directory is created fresh for every call and is unique across concurrent
     * reviews, so two reviews never share a checkout. If the clone fails, the partial directory is
     * removed before the exception is thrown.
     */
    Path cloneRepository(String repositoryUrl, Long reviewId);

    /**
     * PHASE 9 - deletes the temp checkout once the chunks are safely in the database. Best-effort
     * and null-safe, because it runs from a finally block that may execute after a failed clone.
     *
     * <p>STEP 6.2 - recursive (contents first, the repository directory last) and never throws, so
     * a cleanup problem cannot turn a successful review into a failed one. Only per-review
     * directories directly under the configured temp directory are deleted; the shared parent and
     * any unrelated path are refused and logged.
     */
    void deleteRepository(Path path);


}
