package com.aicode.code_review_platform.AI.dto;

import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AIReviewResult {

    private Integer score;

    private String summary;

    private List<String> bugs;

    private List<String> securityIssues;

    private List<String> performanceIssues;

    private List<String> suggestions;

}
