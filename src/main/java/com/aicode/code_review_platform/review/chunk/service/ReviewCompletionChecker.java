package com.aicode.code_review_platform.review.chunk.service;

/**
 * Notified whenever a chunk of a review reaches a terminal state.
 *
 * <p>MILESTONE 3 defines the contract only. The implementation - deciding whether every chunk of
 * the review is finished, aggregating the per-chunk results and completing the parent
 * {@link com.aicode.code_review_platform.review.CodeReview} - belongs to Milestone 4.
 *
 * <p>Until an implementation exists there is no bean of this type, so
 * {@link ChunkProcessingService} resolves it optionally and simply skips the call.
 */
public interface ReviewCompletionChecker {

    /**
     * Called after a chunk of the given review has completed or failed.
     *
     * @param reviewId id of the parent review
     */
    void check(Long reviewId);

}
