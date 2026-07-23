package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;

import java.util.List;

public interface ReviewChunkService {

    List<ReviewChunk> createPendingChunks(CodeReview review, List<CodeChunk> chunks);

    ReviewChunk markStarted(ReviewChunk chunk);

    ReviewChunk markCompleted(ReviewChunk chunk, String prompt, String aiResponse);

    ReviewChunk markFailed(ReviewChunk chunk, String prompt);

}
