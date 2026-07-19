package com.aicode.code_review_platform.review.dto;

import com.aicode.code_review_platform.enums.AppEnums;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CodeReviewResponse {

    private Long id;
    private String language;
    private String code;
    private String reviewResult;
    private AppEnums.ReviewStatus status;
    private LocalDateTime createdAt;
    private AppEnums.ReviewSourceType sourceType;
    private String repositoryUrl;
}
