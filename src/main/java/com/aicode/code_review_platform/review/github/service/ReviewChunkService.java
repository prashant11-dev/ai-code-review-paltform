package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;

import java.util.List;

public interface ReviewChunkService {

    /**
     * PHASE 6 - persists one PENDING row per chunk, each with its prompt already rendered. This is
     * the point where the work becomes durable and independent of the cloned files.
     */
    List<ReviewChunk> createPendingChunks(CodeReview review, List<CodeChunk> chunks);

    /** Consumer side: chunk dequeued, AI call about to start. PENDING -> PROCESSING. */
    ReviewChunk markStarted(ReviewChunk chunk);

    /** Consumer side: AI returned a result. PROCESSING -> COMPLETED, response stored. */
    ReviewChunk markCompleted(ReviewChunk chunk, String prompt, String aiResponse);

    /** Consumer side: AI call failed. PROCESSING -> FAILED, no response stored. */
    ReviewChunk markFailed(ReviewChunk chunk, String prompt);

}
