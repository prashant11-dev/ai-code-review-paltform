package com.aicode.code_review_platform.review.chunk.exception;

/**
 * Raised when processing a single {@link com.aicode.code_review_platform.review.ReviewChunk}
 * failed - a missing file set, a prompt that could not be built, or an AI call that did not
 * return a usable result.
 *
 * <p>By the time this is thrown the chunk has already been marked FAILED, so the exception is
 * about surfacing the failure to the broker, not about recording it.
 */
public class ChunkProcessingException extends RuntimeException {

    public ChunkProcessingException(String message) {
        super(message);
    }

    public ChunkProcessingException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }

}
