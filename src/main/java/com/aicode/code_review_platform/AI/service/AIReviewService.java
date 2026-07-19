package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;

public interface AIReviewService {

    AIReviewResult review(
            ReviewContext context
    );

}
