package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.service.ReviewChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIChunkReviewServiceImpl implements AIChunkReviewService {

    private final AIReviewService aiReviewService;

    private final ReviewChunkService reviewChunkService;

    private final ObjectMapper objectMapper;

    @Override
    public List<AIReviewResult> reviewChunks(
            List<ReviewChunk> reviewChunks,
            List<CodeChunk> chunks
    ) {

        Map<Integer, ReviewChunk> reviewChunksByNumber = reviewChunks.stream()
                .collect(Collectors.toMap(ReviewChunk::getChunkNumber, Function.identity()));

        List<AIReviewResult> reviews = new ArrayList<>();

        for (CodeChunk chunk : chunks) {

            ReviewChunk reviewChunk = reviewChunksByNumber.get(chunk.getChunkNumber());

            log.info(
                    "Reviewing chunk {} containing {} files",
                    chunk.getChunkNumber(),
                    chunk.getFiles().size()
            );

            ReviewContext context = ReviewContext.builder()
                    .files(chunk.getFiles())
                    .build();

            String prompt = aiReviewService.buildPrompt(context);

            reviewChunkService.markStarted(reviewChunk);

            try {
                AIReviewResult result = aiReviewService.review(context);

                reviewChunkService.markCompleted(reviewChunk, prompt, objectMapper.writeValueAsString(result));

                reviews.add(result);
            } catch (RuntimeException e) {
                reviewChunkService.markFailed(reviewChunk, prompt);
                throw e;
            }
        }

        return reviews;
    }

}
