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

}
