package com.aicode.code_review_platform.review.chunk.exception;

/**
 * Raised when a queued {@code ChunkReviewMessage} points at a
 * {@link com.aicode.code_review_platform.review.ReviewChunk} that no longer exists.
 *
 * <p>Unrecoverable by definition: there is no row to mark FAILED and no files to review, so the
 * message can only be rejected.
 */
public class ReviewChunkNotFoundException extends RuntimeException {

    public ReviewChunkNotFoundException(Long reviewChunkId) {
        super("ReviewChunk not found for id " + reviewChunkId);
    }

}
