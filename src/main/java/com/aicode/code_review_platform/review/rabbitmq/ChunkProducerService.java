package com.aicode.code_review_platform.review.rabbitmq;

import com.aicode.code_review_platform.review.ReviewChunk;

/**
 * Publishes persisted review chunks for asynchronous AI processing.
 */
public interface ChunkProducerService {

    /**
     * Publishes the given chunk to the chunk review queue.
     *
     * <p>Takes the entity rather than its identifier so that additional message metadata can be
     * derived later without changing this contract.
     *
     * @throws com.aicode.code_review_platform.review.rabbitmq.exception.ChunkPublishException
     *         if the chunk could not be handed to the broker
     */
    void publish(ReviewChunk reviewChunk);

}
