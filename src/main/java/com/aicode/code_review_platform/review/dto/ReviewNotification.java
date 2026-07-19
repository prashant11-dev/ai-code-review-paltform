package com.aicode.code_review_platform.review.dto;

import com.aicode.code_review_platform.enums.AppEnums;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReviewNotification {

    private Long reviewId;

    private AppEnums.ReviewStatus status;

    private String message;

}
