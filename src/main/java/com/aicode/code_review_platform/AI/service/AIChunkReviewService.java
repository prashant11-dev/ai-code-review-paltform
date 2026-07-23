package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;

import java.util.List;

public interface AIChunkReviewService {

    List<AIReviewResult> reviewChunks(
            List<ReviewChunk> reviewChunks,
            List<CodeChunk> chunks
    );

}
