package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.enums.AppEnums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewChunkRepository extends JpaRepository<ReviewChunk, Long> {

    List<ReviewChunk> findByReviewIdOrderByChunkNumberAsc(Long reviewId);

    List<ReviewChunk> findByReviewIdAndStatus(Long reviewId, AppEnums.ReviewStatus status);

    Optional<ReviewChunk> findByReviewIdAndChunkNumber(Long reviewId, Integer chunkNumber);

    /**
     * MILESTONE 3 - loads a chunk together with its parent review in one query.
     *
     * <p>The asynchronous consumer runs outside any open persistence context, so touching the
     * lazy {@code review} association afterwards would blow up. Fetching it eagerly here keeps
     * {@code chunk.getReview().getId()} safe for the completion-checker callback.
     */
    @Query("SELECT c FROM ReviewChunk c JOIN FETCH c.review WHERE c.id = :id")
    Optional<ReviewChunk> findByIdWithReview(@Param("id") Long id);

    /** MILESTONE 4 - how many chunks the review was split into, for logging and sanity checks. */
    long countByReviewId(Long reviewId);

    /**
     * MILESTONE 4 - the completion test.
     *
     * <p>Every finished chunk triggers a completion check, so this runs once per chunk. Counting
     * in the database keeps that cheap: deciding whether a review is still running never loads a
     * chunk row, however many hundreds of them there are.
     */
    long countByReviewIdAndStatus(Long reviewId, AppEnums.ReviewStatus status);

    /**
     * MILESTONE 4 - aggregation input.
     *
     * <p>Projects only the stored response, deliberately leaving the rendered prompt behind:
     * aggregation never reads it, and it is by far the largest column on the row.
     */
    @Query("""
            SELECT new com.aicode.code_review_platform.review.ChunkAiResponse(c.chunkNumber, c.aiResponse)
              FROM ReviewChunk c
             WHERE c.review.id = :reviewId
               AND c.status = :status
             ORDER BY c.chunkNumber ASC
            """)
    List<ChunkAiResponse> findAiResponsesByReviewIdAndStatus(
            @Param("reviewId") Long reviewId,
            @Param("status") AppEnums.ReviewStatus status
    );

    /** MILESTONE 4 - chunk numbers in a given state, used to report which chunks failed. */
    @Query("""
            SELECT c.chunkNumber
              FROM ReviewChunk c
             WHERE c.review.id = :reviewId
               AND c.status = :status
             ORDER BY c.chunkNumber ASC
            """)
    List<Integer> findChunkNumbersByReviewIdAndStatus(
            @Param("reviewId") Long reviewId,
            @Param("status") AppEnums.ReviewStatus status
    );

}
