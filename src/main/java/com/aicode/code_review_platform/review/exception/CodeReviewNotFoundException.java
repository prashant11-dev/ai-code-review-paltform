package com.aicode.code_review_platform.review.exception;

/**
 * Raised when a review id taken from a persisted
 * {@link com.aicode.code_review_platform.review.ReviewChunk} no longer resolves to a
 * {@link com.aicode.code_review_platform.review.CodeReview}.
 *
 * <p>Unrecoverable by definition: without the parent row there is nothing to finalize, so the
 * completion check can only surface the inconsistency rather than work around it.
 */
public class CodeReviewNotFoundException extends RuntimeException {

    public CodeReviewNotFoundException(Long reviewId) {
        super("CodeReview not found for id " + reviewId);
    }

}
