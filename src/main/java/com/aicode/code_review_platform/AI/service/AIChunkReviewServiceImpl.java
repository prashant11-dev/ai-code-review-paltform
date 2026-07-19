package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIChunkReviewServiceImpl implements AIChunkReviewService {

    private final AIReviewService aiReviewService;

    @Override
    public List<AIReviewResult> reviewChunks(
            List<CodeChunk> chunks
    ) {

        List<AIReviewResult> reviews = new ArrayList<>();

        for (CodeChunk chunk : chunks) {

            log.info(
                    "Reviewing chunk {} containing {} files",
                    chunk.getChunkNumber(),
                    chunk.getFiles().size()
            );

            ReviewContext context = ReviewContext.builder()
                    .files(chunk.getFiles())
                    .build();

            AIReviewResult result = aiReviewService.review(context);

            reviews.add(result);
        }

        return reviews;
    }

}
