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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MILESTONE 4 - unit tests for the completion check.
 *
 * <p>Everything below the checker is mocked apart from the {@link ObjectMapper}: the stored chunk
 * responses are real serialized JSON, so the tests exercise the actual round-trip the checker
 * performs rather than a stand-in for it.
 */
@ExtendWith(MockitoExtension.class)
class ReviewCompletionCheckerImplTest {

    private static final Long REVIEW_ID = 42L;

    @Mock
    private CodeReviewRepo codeReviewRepo;

    @Mock
    private ReviewChunkRepository reviewChunkRepository;

    @Mock
    private ReviewAggregatorService reviewAggregatorService;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private ReviewCompletionCheckerImpl checker;

    @BeforeEach
    void setUp() {

        checker = new ReviewCompletionCheckerImpl(
                codeReviewRepo,
                reviewChunkRepository,
                reviewAggregatorService,
                notificationService,
                objectMapper
        );
    }

    // Test 1 - a chunk is still running, so nothing may be finalized yet.
    @Test
    void doesNotFinalizeWhileAChunkIsStillProcessing() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(3L, 0L, 1L);

        checker.check(REVIEW_ID);

        verifyNoInteractions(reviewAggregatorService, notificationService);
        verifyNothingWasFinalized();
    }

    // Test 1b - a chunk still sitting in the queue counts the same as one being processed.
    @Test
    void doesNotFinalizeWhileAChunkIsStillPending() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(3L, 1L, 0L);

        checker.check(REVIEW_ID);

        verifyNoInteractions(reviewAggregatorService, notificationService);
        verifyNothingWasFinalized();
    }

    // Test 2 - every chunk succeeded: aggregate, persist, notify.
    @Test
    void finalizesTheReviewOnceEveryChunkHasCompleted() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(3L, 0L, 0L);
        givenCompletedChunks(
                chunkResult(1, 80, "Chunk one summary."),
                chunkResult(2, 90, "Chunk two summary."),
                chunkResult(3, 70, "Chunk three summary.")
        );
        givenFailedChunkNumbers();

        AIReviewResult aggregated = AIReviewResult.builder()
                .score(80)
                .summary("Aggregated summary.")
                .bugs(List.of("a bug"))
                .build();

        givenAggregatorReturns(aggregated);
        givenFinalizeReturns(1);

        checker.check(REVIEW_ID);

        // The aggregator has to see every successful chunk, not a subset.
        ArgumentCaptor<RepositoryReviewContext> context = ArgumentCaptor.forClass(RepositoryReviewContext.class);
        verify(reviewAggregatorService).aggregate(context.capture());
        assertThat(context.getValue().getChunkReviews()).hasSize(3);

        FinalizeCall call = captureFinalizeCall();

        assertThat(call.status()).isEqualTo(AppEnums.ReviewStatus.COMPLETED);
        assertThat(call.score()).isEqualTo(80);
        assertThat(call.summary()).isEqualTo("Aggregated summary.");
        assertThat(call.result().getBugs()).containsExactly("a bug");
        assertThat(call.result().getFailedChunks()).isEmpty();
        assertThat(call.updatedAt()).isNotNull();

        // The guard is what makes a concurrent second finalization a no-op.
        assertThat(call.expectedStatuses())
                .containsExactlyInAnyOrder(AppEnums.ReviewStatus.PENDING, AppEnums.ReviewStatus.PROCESSING);

        verify(notificationService).notifyReviewCompleted(REVIEW_ID);
        verify(notificationService, never()).notifyReviewFailed(any());
    }

    // Test 3 - one chunk failed. The review still finalizes, but the failure is reported.
    @Test
    void finalizesTheReviewButReportsTheFailedChunk() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(3L, 0L, 0L);
        givenCompletedChunks(
                chunkResult(1, 80, "Chunk one summary."),
                chunkResult(3, 60, "Chunk three summary.")
        );
        givenFailedChunkNumbers(2);

        givenAggregatorReturns(AIReviewResult.builder().score(70).summary("Partial summary.").build());
        givenFinalizeReturns(1);

        checker.check(REVIEW_ID);

        // Only the two survivors are aggregation input - a failed chunk contributes nothing.
        ArgumentCaptor<RepositoryReviewContext> context = ArgumentCaptor.forClass(RepositoryReviewContext.class);
        verify(reviewAggregatorService).aggregate(context.capture());
        assertThat(context.getValue().getChunkReviews()).hasSize(2);

        FinalizeCall call = captureFinalizeCall();

        assertThat(call.status()).isEqualTo(AppEnums.ReviewStatus.COMPLETED);
        assertThat(call.result().getFailedChunks()).containsExactly(2);

        verify(notificationService).notifyReviewCompleted(REVIEW_ID);
    }

    // Test 3b - a chunk marked COMPLETED whose stored response is unreadable is reported as failed
    // rather than taking the whole review down.
    @Test
    void reportsACompletedChunkWhoseStoredResponseCannotBeParsed() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(2L, 0L, 0L);

        when(reviewChunkRepository.findAiResponsesByReviewIdAndStatus(REVIEW_ID, AppEnums.ReviewStatus.COMPLETED))
                .thenReturn(List.of(
                        chunkResult(1, 80, "Chunk one summary."),
                        new ChunkAiResponse(2, "this is not json")
                ));

        givenFailedChunkNumbers();
        givenAggregatorReturns(AIReviewResult.builder().score(80).summary("Partial summary.").build());
        givenFinalizeReturns(1);

        checker.check(REVIEW_ID);

        ArgumentCaptor<RepositoryReviewContext> context = ArgumentCaptor.forClass(RepositoryReviewContext.class);
        verify(reviewAggregatorService).aggregate(context.capture());
        assertThat(context.getValue().getChunkReviews()).hasSize(1);

        FinalizeCall call = captureFinalizeCall();

        assertThat(call.status()).isEqualTo(AppEnums.ReviewStatus.COMPLETED);
        assertThat(call.result().getFailedChunks()).containsExactly(2);
    }

    // Test 4 - every chunk failed. Nothing is aggregated and the review is marked FAILED.
    @Test
    void marksTheReviewFailedWhenEveryChunkFailed() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(2L, 0L, 0L);
        givenCompletedChunks();
        givenFailedChunkNumbers(1, 2);
        givenFinalizeReturns(1);

        checker.check(REVIEW_ID);

        verifyNoInteractions(reviewAggregatorService);

        FinalizeCall call = captureFinalizeCall();

        assertThat(call.status()).isEqualTo(AppEnums.ReviewStatus.FAILED);
        // No fabricated score - the AI never rated anything.
        assertThat(call.score()).isNull();
        assertThat(call.summary()).contains("All 2 chunk(s) failed");
        assertThat(call.result().getFailedChunks()).containsExactly(1, 2);

        verify(notificationService).notifyReviewFailed(REVIEW_ID);
        verify(notificationService, never()).notifyReviewCompleted(any());
    }

    // Test 4b - aggregation itself blowing up must not produce a COMPLETED review.
    @Test
    void marksTheReviewFailedWhenAggregationThrows() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(1L, 0L, 0L);
        givenCompletedChunks(chunkResult(1, 80, "Chunk one summary."));
        givenFailedChunkNumbers();
        givenFinalizeReturns(1);

        when(reviewAggregatorService.aggregate(any())).thenThrow(new IllegalStateException("boom"));

        checker.check(REVIEW_ID);

        FinalizeCall call = captureFinalizeCall();

        assertThat(call.status()).isEqualTo(AppEnums.ReviewStatus.FAILED);
        assertThat(call.summary()).contains("Aggregation of the chunk results failed");

        verify(notificationService).notifyReviewFailed(REVIEW_ID);
        verify(notificationService, never()).notifyReviewCompleted(any());
    }

    // Test 5 - a second check after the review was finalized must not aggregate or notify again.
    @Test
    void doesNotFinalizeAnAlreadyFinalizedReviewTwice() {

        CodeReview review = givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);

        givenChunkCounts(2L, 0L, 0L);
        givenCompletedChunks(
                chunkResult(1, 80, "Chunk one summary."),
                chunkResult(2, 90, "Chunk two summary.")
        );
        givenFailedChunkNumbers();
        givenAggregatorReturns(AIReviewResult.builder().score(85).summary("Aggregated summary.").build());

        // The finalizing update is what moves the row, so the second check reads it as terminal -
        // exactly what a second consumer would see in the database.
        when(codeReviewRepo.finalizeReview(eq(REVIEW_ID), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    review.setStatus(AppEnums.ReviewStatus.COMPLETED);
                    return 1;
                });

        checker.check(REVIEW_ID);
        checker.check(REVIEW_ID);

        verify(reviewAggregatorService, times(1)).aggregate(any());
        verify(codeReviewRepo, times(1)).finalizeReview(any(), any(), any(), any(), any(), any(), any());
        verify(notificationService, times(1)).notifyReviewCompleted(REVIEW_ID);
    }

    // Test 5b - the race the status check cannot close: two consumers both reach the update and
    // the loser must stay silent instead of overwriting and re-notifying.
    @Test
    void discardsItsResultWhenAnotherConsumerWonTheFinalizationRace() {

        givenReviewInStatus(AppEnums.ReviewStatus.PROCESSING);
        givenChunkCounts(1L, 0L, 0L);
        givenCompletedChunks(chunkResult(1, 80, "Chunk one summary."));
        givenFailedChunkNumbers();
        givenAggregatorReturns(AIReviewResult.builder().score(80).summary("Aggregated summary.").build());

        // Zero rows updated - the guard matched nothing, so somebody else finalized first.
        givenFinalizeReturns(0);

        checker.check(REVIEW_ID);

        verifyNoInteractions(notificationService);
    }

    // Test 5c - a redelivered chunk of a review that finished long ago costs two queries and stops.
    @Test
    void returnsImmediatelyWhenTheReviewIsAlreadyCompleted() {

        givenReviewInStatus(AppEnums.ReviewStatus.COMPLETED);

        checker.check(REVIEW_ID);

        verifyNoInteractions(reviewChunkRepository, reviewAggregatorService, notificationService);
        verifyNothingWasFinalized();
    }

    // Test 6 - a chunk pointing at a review that no longer exists.
    @Test
    void throwsTheProjectExceptionWhenTheReviewDoesNotExist() {

        when(codeReviewRepo.findById(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checker.check(REVIEW_ID))
                .isInstanceOf(CodeReviewNotFoundException.class)
                .hasMessageContaining(String.valueOf(REVIEW_ID));

        verifyNoInteractions(reviewChunkRepository, reviewAggregatorService, notificationService);
    }

    private CodeReview givenReviewInStatus(AppEnums.ReviewStatus status) {

        CodeReview review = CodeReview.builder()
                .id(REVIEW_ID)
                .sourceType(AppEnums.ReviewSourceType.GITHUB)
                .repositoryUrl("https://github.com/example/repo.git")
                .status(status)
                .build();

        when(codeReviewRepo.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        return review;
    }

    private void givenChunkCounts(long total, long pending, long processing) {

        when(reviewChunkRepository.countByReviewId(REVIEW_ID)).thenReturn(total);
        when(reviewChunkRepository.countByReviewIdAndStatus(REVIEW_ID, AppEnums.ReviewStatus.PENDING)).thenReturn(pending);
        when(reviewChunkRepository.countByReviewIdAndStatus(REVIEW_ID, AppEnums.ReviewStatus.PROCESSING)).thenReturn(processing);
    }

    private void givenCompletedChunks(ChunkAiResponse... responses) {

        when(reviewChunkRepository.findAiResponsesByReviewIdAndStatus(REVIEW_ID, AppEnums.ReviewStatus.COMPLETED))
                .thenReturn(List.of(responses));
    }

    private void givenFailedChunkNumbers(Integer... chunkNumbers) {

        when(reviewChunkRepository.findChunkNumbersByReviewIdAndStatus(REVIEW_ID, AppEnums.ReviewStatus.FAILED))
                .thenReturn(Arrays.asList(chunkNumbers));
    }

    private void givenAggregatorReturns(AIReviewResult result) {
        when(reviewAggregatorService.aggregate(any())).thenReturn(result);
    }

    private void givenFinalizeReturns(int updatedRows) {
        when(codeReviewRepo.finalizeReview(eq(REVIEW_ID), any(), any(), any(), any(), any(), any()))
                .thenReturn(updatedRows);
    }

    /** Serializes a chunk result the same way the chunk consumer stores it. */
    private ChunkAiResponse chunkResult(int chunkNumber, int score, String summary) {

        AIReviewResult result = AIReviewResult.builder()
                .score(score)
                .summary(summary)
                .bugs(List.of())
                .securityIssues(List.of())
                .performanceIssues(List.of())
                .suggestions(List.of())
                .build();

        return new ChunkAiResponse(chunkNumber, objectMapper.writeValueAsString(result));
    }

    private FinalizeCall captureFinalizeCall() {

        ArgumentCaptor<AppEnums.ReviewStatus> status = ArgumentCaptor.forClass(AppEnums.ReviewStatus.class);
        ArgumentCaptor<String> reviewResult = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> score = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AppEnums.ReviewStatus>> expectedStatuses =
                ArgumentCaptor.forClass(Collection.class);

        verify(codeReviewRepo).finalizeReview(
                eq(REVIEW_ID),
                status.capture(),
                reviewResult.capture(),
                score.capture(),
                summary.capture(),
                updatedAt.capture(),
                expectedStatuses.capture()
        );

        return new FinalizeCall(
                status.getValue(),
                objectMapper.readValue(reviewResult.getValue(), AIReviewResult.class),
                score.getValue(),
                summary.getValue(),
                updatedAt.getValue(),
                expectedStatuses.getValue()
        );
    }

    private void verifyNothingWasFinalized() {
        verify(codeReviewRepo, never()).finalizeReview(any(), any(), any(), any(), any(), any(), any());
    }

    /** What the checker actually wrote, with the persisted JSON parsed back into a result. */
    private record FinalizeCall(
            AppEnums.ReviewStatus status,
            AIReviewResult result,
            Integer score,
            String summary,
            LocalDateTime updatedAt,
            Collection<AppEnums.ReviewStatus> expectedStatuses
    ) {
    }

}
