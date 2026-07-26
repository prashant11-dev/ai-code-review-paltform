package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.AI.service.AIReviewService;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.ReviewChunkRepository;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.mapper.ReviewChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewChunkServiceImpl implements ReviewChunkService {

    private final ReviewChunkRepository reviewChunkRepository;

    private final ReviewChunkMapper reviewChunkMapper;

    private final AIReviewService aiReviewService;

    /**
     * Persists each chunk together with the prompt built from its files. Storing the prompt up
     * front makes the chunk self-contained, so an asynchronous consumer can process it from the
     * database alone after the cloned repository has been deleted.
     */
    @Override
    public List<ReviewChunk> createPendingChunks(CodeReview review, List<CodeChunk> chunks) {

        List<ReviewChunk> reviewChunks = chunks.stream()
                .map(chunk -> reviewChunkMapper.toEntity(review, chunk, buildPrompt(chunk)))
                .collect(Collectors.toList());

        List<ReviewChunk> saved = reviewChunkRepository.saveAll(reviewChunks);

        log.info("Persisted {} pending chunk(s) for review id: {}", saved.size(), review.getId());

        return saved;
    }

    @Override
    public ReviewChunk markStarted(ReviewChunk chunk) {

        chunk.setStatus(AppEnums.ReviewStatus.PROCESSING);
        chunk.setStartedAt(LocalDateTime.now());

        return reviewChunkRepository.save(chunk);
    }

    @Override
    public ReviewChunk markCompleted(ReviewChunk chunk, String prompt, String aiResponse) {

        chunk.setStatus(AppEnums.ReviewStatus.COMPLETED);
        chunk.setPrompt(prompt);
        chunk.setAiResponse(aiResponse);
        chunk.setCompletedAt(LocalDateTime.now());
        chunk.setProcessingTimeMs(processingTimeMs(chunk));

        return reviewChunkRepository.save(chunk);
    }

    @Override
    public ReviewChunk markFailed(ReviewChunk chunk, String prompt) {

        chunk.setStatus(AppEnums.ReviewStatus.FAILED);
        chunk.setPrompt(prompt);
        chunk.setCompletedAt(LocalDateTime.now());
        chunk.setProcessingTimeMs(processingTimeMs(chunk));

        return reviewChunkRepository.save(chunk);
    }

    private String buildPrompt(CodeChunk chunk) {

        return aiReviewService.buildPrompt(
                ReviewContext.builder()
                        .files(chunk.getFiles())
                        .build()
        );
    }

    private Long processingTimeMs(ReviewChunk chunk) {

        if (chunk.getStartedAt() == null) {
            return null;
        }

        return Duration.between(chunk.getStartedAt(), chunk.getCompletedAt()).toMillis();
    }

}
