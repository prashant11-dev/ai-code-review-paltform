package com.aicode.code_review_platform.review.dto;

import com.aicode.code_review_platform.enums.AppEnums;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CodeReviewRequest {

    @NotBlank
    private String language;

    @NotBlank
    private String code;

}