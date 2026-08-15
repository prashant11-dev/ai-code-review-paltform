package com.aicode.code_review_platform.review.chunk.service;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.RepositoryReviewContext;
import com.aicode.code_review_platform.AI.service.ReviewAggregatorService;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.ChunkAiResponse;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.CodeReviewRepo;
import com.aicode.code_review_platform.review.ReviewChunkRepository;
import com.aicode.code_review_platform.review.exception.CodeReviewNotFoundException;
import com.aicode.code_review_platform.review.websocket.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * MILESTONE 4 - turns finished chunks back into a finished review.
 *
 * <p>Called once per chunk that reaches a terminal state. Almost every one of those calls does
 * nothing but two counts, because only the last chunk of a review finds nothing still running.
 * That call then aggregates the stored per-chunk results, writes the parent
 * {@link CodeReview} and notifies the client.
 *
 * <p>No AI call happens here. Every result being aggregated was produced and persisted earlier by
 * {@link ChunkProcessingService}, which is what makes this step cheap enough to run after every
 * single chunk.
 *
 * <p>Deliberately <em>not</em> annotated {@code @Transactional}. The only write is the single
 * atomic statement in {@link CodeReviewRepo#finalizeReview}, which carries its own transaction, so
 * there is nothing to hold open while parsing several hundred stored JSON responses - and the
 * notification that follows it is genuinely sent after the row is committed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewCompletionCheckerImpl implements ReviewCompletionChecker {

    /**
     * States a review may still be finalized from. Doubles as the predicate of the atomic claim in
     * {@link CodeReviewRepo#finalizeReview} - anything outside this set has already been finalized.
     */
    private static final List<AppEnums.ReviewStatus> FINALIZABLE_STATUSES = List.of(
            AppEnums.ReviewStatus.PENDING,
            AppEnums.ReviewStatus.PROCESSING
    );

    private final CodeReviewRepo codeReviewRepo;

    private final ReviewChunkRepository reviewChunkRepository;

    private final ReviewAggregatorService reviewAggregatorService;

    private final NotificationService notificationService;

    private final ObjectMapper objectMapper;

    @Override
    public void check(Long reviewId) {

        log.info("Checking completion status for review {}", reviewId);

        CodeReview review = codeReviewRepo.findById(reviewId)
                .orElseThrow(() -> new CodeReviewNotFoundException(reviewId));

        // Cheap short-circuit for the common repeat case - a redelivered chunk, or a consumer
        // arriving after the review was already wrapped up. The real race is closed further down
        // by the conditional update; this just avoids doing the work to lose it.
        if (isFinalized(review.getStatus())) {
            log.info("Review {} has already been finalized with status {}", reviewId, review.getStatus());
            return;
        }

        long totalChunks = reviewChunkRepository.countByReviewId(reviewId);
        long pendingChunks = reviewChunkRepository.countByReviewIdAndStatus(reviewId, AppEnums.ReviewStatus.PENDING);
        long processingChunks = reviewChunkRepository.countByReviewIdAndStatus(reviewId, AppEnums.ReviewStatus.PROCESSING);

        log.info("Review {} has {} chunks", reviewId, totalChunks);

        // Any chunk still queued or running means the remaining results are not on record yet.
        // Aggregating now would silently ship a partial review.
        if (pendingChunks > 0 || processingChunks > 0) {
            log.info("Review {} still has {} pending chunks", reviewId, pendingChunks);
            log.info("Review {} still has {} processing chunks", reviewId, processingChunks);
            return;
        }

        log.info("All chunks completed for review {}", reviewId);

        finalizeReview(review, totalChunks);
    }

    private void finalizeReview(CodeReview review, long totalChunks) {

        Long reviewId = review.getId();

        ParsedResults parsed = parseCompletedResults(reviewId);

        List<Integer> failedChunks = collectFailedChunks(reviewId, parsed.unusableChunks());

        if (!failedChunks.isEmpty()) {
            log.warn("Review {} contains {} failed chunks: {}", reviewId, failedChunks.size(), failedChunks);
        }

        // Nothing survived, so there is nothing honest to aggregate. Reporting an empty review as
        // COMPLETED would tell the client the repository is clean when it was never looked at.
        if (parsed.results().isEmpty()) {
            failReview(
                    reviewId,
                    totalChunks == 0
                            ? "Review finalized with no chunks to process."
                            : "All " + totalChunks + " chunk(s) failed during processing.",
                    failedChunks
            );
            return;
        }

        AIReviewResult finalResult;

        try {
            log.info("Aggregating {} successful chunk results for review {}", parsed.results().size(), reviewId);

            finalResult = reviewAggregatorService.aggregate(
                    RepositoryReviewContext.builder()
                            .review(review)
                            .chunkReviews(parsed.results())
                            .build()
            );

        } catch (RuntimeException e) {
            // The per-chunk results are all on record and the chunks themselves are terminal, so
            // retrying would only reproduce this. Record the failure instead of leaving the review
            // in PROCESSING, where a client would poll it forever. The cause is logged in full
            // rather than rethrown: rethrowing would fail a chunk that actually succeeded, and the
            // FAILED status written below is the durable evidence.
            log.error("Aggregation failed for review {}, marking it FAILED", reviewId, e);

            failReview(reviewId, "Aggregation of the chunk results failed: " + e.getMessage(), failedChunks);
            return;
        }

        // Attached after aggregation rather than inside the aggregator: which chunks failed is a
        // property of how this review was processed, not of the AI results being merged.
        finalResult.setFailedChunks(failedChunks);

        persist(reviewId, AppEnums.ReviewStatus.COMPLETED, finalResult);
    }

    /**
     * Reads back what the chunk consumers stored. A chunk whose response cannot be parsed is
     * reported as failed rather than aborting the review - the other chunks are still perfectly
     * good aggregation input, and losing all of them over one bad row helps nobody.
     */
    private ParsedResults parseCompletedResults(Long reviewId) {

        List<ChunkAiResponse> responses = reviewChunkRepository.findAiResponsesByReviewIdAndStatus(
                reviewId,
                AppEnums.ReviewStatus.COMPLETED
        );

        List<AIReviewResult> results = new ArrayList<>();
        List<Integer> unusable = new ArrayList<>();

        for (ChunkAiResponse response : responses) {

            if (response.aiResponse() == null || response.aiResponse().isBlank()) {
                log.warn("Chunk {} of review {} is COMPLETED but stored no AI response", response.chunkNumber(), reviewId);
                unusable.add(response.chunkNumber());
                continue;
            }

            try {
                results.add(objectMapper.readValue(response.aiResponse(), AIReviewResult.class));
            } catch (RuntimeException e) {
                log.error("Failed to parse the stored AI response of chunk {} for review {}", response.chunkNumber(), reviewId, e);
                unusable.add(response.chunkNumber());
            }
        }

        return new ParsedResults(results, unusable);
    }

    /**
     * Chunks that produced no usable result: the ones the consumer marked FAILED, plus the ones
     * that completed but whose stored response could not be read back.
     */
    private List<Integer> collectFailedChunks(Long reviewId, List<Integer> unusableChunks) {

        Set<Integer> failed = new TreeSet<>(reviewChunkRepository.findChunkNumbersByReviewIdAndStatus(
                reviewId,
                AppEnums.ReviewStatus.FAILED
        ));

        failed.addAll(unusableChunks);

        return List.copyOf(failed);
    }

    private void failReview(Long reviewId, String summary, List<Integer> failedChunks) {

        log.error("Review {} produced no usable chunk result: {}", reviewId, summary);

        // Score left null on purpose: there is no result to score, and a zero would read as a
        // review the AI actually ran and rated badly. The empty lists keep the payload the same
        // shape a client already handles.
        AIReviewResult result = AIReviewResult.builder()
                .summary(summary)
                .bugs(List.of())
                .securityIssues(List.of())
                .performanceIssues(List.of())
                .suggestions(List.of())
                .failedChunks(failedChunks)
                .build();

        persist(reviewId, AppEnums.ReviewStatus.FAILED, result);
    }

    private void persist(Long reviewId, AppEnums.ReviewStatus status, AIReviewResult result) {

        int updated = codeReviewRepo.finalizeReview(
                reviewId,
                status,
                objectMapper.writeValueAsString(result),
                result.getScore(),
                result.getSummary(),
                LocalDateTime.now(),
                FINALIZABLE_STATUSES
        );

        // Another consumer finalized the review between the status check and this update. Its
        // result was aggregated from the same rows and is just as valid, so discard ours rather
        // than overwrite - and stay silent on the WebSocket, because it has already notified.
        if (updated == 0) {
            log.info("Review {} has already been finalized by another consumer, discarding this result", reviewId);
            return;
        }

        log.info("Final review saved for review {} with status {}", reviewId, status);

        if (status == AppEnums.ReviewStatus.COMPLETED) {
            notificationService.notifyReviewCompleted(reviewId);
        } else {
            notificationService.notifyReviewFailed(reviewId);
        }

        log.info("WebSocket notification sent for review {}", reviewId);
    }

    private boolean isFinalized(AppEnums.ReviewStatus status) {
        return status == null || !FINALIZABLE_STATUSES.contains(status);
    }

    /**
     * @param results        the responses that parsed back into usable results
     * @param unusableChunks chunk numbers that were COMPLETED but whose response could not be read
     */
    private record ParsedResults(List<AIReviewResult> results, List<Integer> unusableChunks) {
    }

}
