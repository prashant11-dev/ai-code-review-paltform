package com.aicode.code_review_platform.AI.dto;

import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class RepositoryReviewContext {

    private CodeReview review;

    private List<CodeChunk> chunks;

    private List<AIReviewResult> chunkReviews;

}
