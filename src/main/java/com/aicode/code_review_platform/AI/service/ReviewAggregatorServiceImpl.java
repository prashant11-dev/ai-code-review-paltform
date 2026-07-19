package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.RepositoryReviewContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Service
public class ReviewAggregatorServiceImpl implements ReviewAggregatorService {

    @Override
    public AIReviewResult aggregate(RepositoryReviewContext context) {

        List<AIReviewResult> results = context.getChunkReviews();

        if (results.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate an empty list of reviews");
        }

        if (results.size() == 1) {
            return results.get(0);
        }

        log.info("Aggregating {} chunk reviews into one", results.size());

        int averageScore = (int) Math.round(
                results.stream()
                        .mapToInt(AIReviewResult::getScore)
                        .average()
                        .orElse(0)
        );

        String summary = results.stream()
                .map(AIReviewResult::getSummary)
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse("");

        return AIReviewResult.builder()
                .score(averageScore)
                .summary(summary)
                .bugs(mergeDistinct(results, AIReviewResult::getBugs))
                .securityIssues(mergeDistinct(results, AIReviewResult::getSecurityIssues))
                .performanceIssues(mergeDistinct(results, AIReviewResult::getPerformanceIssues))
                .suggestions(mergeDistinct(results, AIReviewResult::getSuggestions))
                .build();
    }

    private List<String> mergeDistinct(
            List<AIReviewResult> results,
            java.util.function.Function<AIReviewResult, List<String>> extractor
    ) {

        LinkedHashSet<String> merged = new LinkedHashSet<>();

        for (AIReviewResult result : results) {
            List<String> values = extractor.apply(result);
            if (values != null) {
                merged.addAll(values);
            }
        }

        return List.copyOf(merged);
    }

}
