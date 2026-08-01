package com.aicode.code_review_platform.review.chunk.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.AI.service.AIReviewService;
import com.aicode.code_review_platform.AI.service.PromptBuilderService;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.ReviewChunkFile;
import com.aicode.code_review_platform.review.ReviewChunkFileRepository;
import com.aicode.code_review_platform.review.ReviewChunkRepository;
import com.aicode.code_review_platform.review.chunk.exception.ChunkProcessingException;
import com.aicode.code_review_platform.review.chunk.exception.ReviewChunkNotFoundException;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import com.aicode.code_review_platform.review.github.service.ReviewChunkService;
import com.aicode.code_review_platform.review.mapper.ReviewChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * MILESTONE 3 - the single-chunk pipeline.
 *
 * <p>Deliberately <em>not</em> annotated {@code @Transactional}. Each state change is committed on
 * its own through {@link ReviewChunkService}, because a single surrounding transaction would roll
 * back the FAILED marker on the very exception that caused it, leaving the chunk stuck in
 * PROCESSING forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkProcessingServiceImpl implements ChunkProcessingService {

    private final ReviewChunkRepository reviewChunkRepository;

    private final ReviewChunkFileRepository reviewChunkFileRepository;

    private final ReviewChunkService reviewChunkService;

    private final ReviewChunkMapper reviewChunkMapper;

    private final PromptBuilderService promptBuilderService;

    private final AIReviewService aiReviewService;

    private final ObjectMapper objectMapper;

    /**
     * Resolved lazily and optionally: Milestone 3 ships the {@link ReviewCompletionChecker}
     * contract without an implementation, so there is no bean to inject yet. A hard dependency
     * would stop the application from starting. Milestone 4 only has to add the bean - nothing
     * here changes.
     */
    private final ObjectProvider<ReviewCompletionChecker> reviewCompletionChecker;

    @Override
    public void process(Long reviewChunkId) {

        log.info("Loading ReviewChunk {}", reviewChunkId);

        // Fetched with its review, so the completion-checker callback below can read the review id
        // without tripping over a lazy association outside the persistence context.
        ReviewChunk chunk = reviewChunkRepository.findByIdWithReview(reviewChunkId)
                .orElseThrow(() -> new ReviewChunkNotFoundException(reviewChunkId));

        Long reviewId = chunk.getReview().getId();

        // A redelivery of an already finished chunk must not spend another AI call, and must not
        // drag a COMPLETED row back through PROCESSING.
        if (chunk.getStatus() == AppEnums.ReviewStatus.COMPLETED) {
            log.info("ReviewChunk {} (reviewId={}) is already COMPLETED, skipping", reviewChunkId, reviewId);
            return;
        }

        reviewChunkService.markStarted(chunk);
        log.info(
                "ReviewChunk {} (reviewId={}, chunkNumber={}) moved to PROCESSING",
                reviewChunkId,
                reviewId,
                chunk.getChunkNumber()
        );

        // Held outside the try so the failure path can still persist whatever prompt was rendered
        // before things went wrong.
        String prompt = null;

        try {
            List<CodeFile> files = loadFiles(chunk);

            log.info("Generating prompt for chunk {} from {} file(s)", reviewChunkId, files.size());
            prompt = promptBuilderService.buildPrompt(files);

            // Persisted before the AI call, not after: if the provider hangs or the worker dies
            // mid-request, the exact input that was sent is still on record.
            persistPrompt(chunk, prompt);
            log.info("Saved prompt for chunk {} ({} characters)", reviewChunkId, prompt.length());

            log.info("Calling AI provider for chunk {}", reviewChunkId);
            AIReviewResult result = aiReviewService.review(
                    ReviewContext.builder()
                            .files(files)
                            .build()
            );
            log.info("AI response received for chunk {}", reviewChunkId);

            // markCompleted also stamps completedAt and derives processingTimeMs from startedAt.
            ReviewChunk completed = reviewChunkService.markCompleted(
                    chunk,
                    prompt,
                    objectMapper.writeValueAsString(result)
            );

            log.info("Chunk {} completed in {} ms", reviewChunkId, completed.getProcessingTimeMs());

        } catch (Exception e) {
            handleFailure(chunk, reviewId, prompt, e);
        }

        // Success path only - handleFailure notifies before it rethrows, because a chunk that
        // failed is still a chunk that will never report again, and Milestone 4 has to see that
        // or the review would sit in PROCESSING forever.
        notifyCompletionChecker(reviewId);
    }

    private List<CodeFile> loadFiles(ReviewChunk chunk) {

        log.info("Loading files for chunk {}", chunk.getId());

        List<ReviewChunkFile> files = reviewChunkFileRepository.findByChunkIdOrderByFileOrderAsc(chunk.getId());

        // The chunk row exists but its files do not - the snapshot taken at submission time is
        // gone or was never written. There is nothing to review, so fail instead of prompting
        // the model with an empty file section.
        if (files.isEmpty()) {
            throw new ChunkProcessingException(
                    "No files persisted for ReviewChunk " + chunk.getId()
            );
        }

        log.debug("Loaded {} file(s) for chunk {}", files.size(), chunk.getId());

        return files.stream()
                .map(reviewChunkMapper::toCodeFile)
                .toList();
    }

    private void persistPrompt(ReviewChunk chunk, String prompt) {
        chunk.setPrompt(prompt);
        reviewChunkRepository.save(chunk);
    }

    private void handleFailure(ReviewChunk chunk, Long reviewId, String prompt, Exception cause) {

        log.error(
                "Chunk {} failed (reviewId={}): {}",
                chunk.getId(),
                reviewId,
                cause.getMessage(),
                cause
        );

        try {
            // Committed in its own transaction, so the chunk leaves PROCESSING even though the
            // exception is about to propagate.
            reviewChunkService.markFailed(chunk, prompt);
        } catch (RuntimeException persistenceFailure) {
            // The database is the problem too - log it, but let the original cause be the one
            // that reaches the caller.
            log.error(
                    "Failed to mark chunk {} as FAILED (reviewId={})",
                    chunk.getId(),
                    reviewId,
                    persistenceFailure
            );
        }

        // Let the completion checker see the terminal state before the exception unwinds.
        notifyCompletionChecker(reviewId);

        throw new ChunkProcessingException(
                "Failed to process ReviewChunk " + chunk.getId(),
                cause
        );
    }

    private void notifyCompletionChecker(Long reviewId) {

        ReviewCompletionChecker checker = reviewCompletionChecker.getIfAvailable();

        if (checker == null) {
            log.debug("No ReviewCompletionChecker configured, skipping completion check for reviewId={}", reviewId);
            return;
        }

        log.info("Notifying completion checker for reviewId={}", reviewId);

        checker.check(reviewId);
    }

}
