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

    /**
     * MILESTONE 4 - chunk numbers that never produced a result.
     *
     * <p>A review is finalized as soon as no chunk is still running, which means it can finish
     * with some of its chunks having failed. Recording them here is what keeps that visible to the
     * client instead of presenting a partial review as a complete one.
     *
     * <p>Null on a per-chunk result: only the aggregated review of a whole repository fills it in.
     */
    private List<Integer> failedChunks;

}
