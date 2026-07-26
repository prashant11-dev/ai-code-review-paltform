package com.aicode.code_review_platform.review.rabbitmq.exception;

/**
 * Raised when a {@link com.aicode.code_review_platform.review.ReviewChunk} could not be
 * published to the broker.
 */
public class ChunkPublishException extends RuntimeException {

    public ChunkPublishException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }

}
