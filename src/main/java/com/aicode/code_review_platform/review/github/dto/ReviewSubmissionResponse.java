package com.aicode.code_review_platform.review.github.dto;

import com.aicode.code_review_platform.enums.AppEnums;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReviewSubmissionResponse {

    private Long id;
    private String repositoryUrl;
    private String reviewResult;
    private AppEnums.ReviewStatus status;
    private LocalDateTime createdAt;

}
