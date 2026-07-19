package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.RepositoryReviewContext;

public interface ReviewAggregatorService {

    AIReviewResult aggregate(
            RepositoryReviewContext context
    );

}
