package com.aicode.code_review_platform.review.chunk.service;

/**
 * Processes exactly one {@link com.aicode.code_review_platform.review.ReviewChunk} end to end:
 * load, prompt, review, persist, notify.
 *
 * <p>This is the whole of the asynchronous work triggered by a {@code ChunkReviewMessage}. It
 * knows nothing about the broker that delivered the id, and nothing about the other chunks of the
 * same review - aggregating those is the completion checker's job.
 */
public interface ChunkProcessingService {

    /**
     * Reviews the chunk with the given id and drives it to a terminal state.
     *
     * <p>The chunk always ends up COMPLETED or FAILED; it is never left in PROCESSING.
     *
     * @param reviewChunkId id of the chunk to process
     * @throws com.aicode.code_review_platform.review.chunk.exception.ReviewChunkNotFoundException
     *         if no chunk exists for the id
     * @throws com.aicode.code_review_platform.review.chunk.exception.ChunkProcessingException
     *         if the chunk could not be reviewed; the chunk has already been marked FAILED
     */
    void process(Long reviewChunkId);

}
