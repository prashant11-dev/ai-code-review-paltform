package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface CodeReviewRepo extends JpaRepository<CodeReview,Long> {

    List<CodeReview> findByUser(User user);

    /**
     * MILESTONE 4 - the atomic finalization guard.
     *
     * <p>Chunks finish on independent RabbitMQ consumers, so several of them can observe "no chunk
     * is still running" at the same instant and all decide to finalize. Rather than coordinating
     * the readers, the write itself is made the arbiter: the status predicate only matches a
     * review that has not been finalized yet, so exactly one caller updates a row and every later
     * caller gets 0 back and knows to discard its result.
     *
     * <p>Status and result move in the same statement, which is why there is no window in which a
     * review reads as COMPLETED with no result attached.
     *
     * <p>Transactional here rather than on the caller, deliberately: this single statement is the
     * only write of the whole completion check, so keeping the transaction around it means the
     * caller can send its WebSocket notification knowing the row is already committed.
     *
     * @return number of rows updated - 1 for the caller that won the race, 0 for everyone else
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CodeReview r
               SET r.status = :status,
                   r.reviewResult = :reviewResult,
                   r.score = :score,
                   r.summary = :summary,
                   r.updatedAt = :updatedAt
             WHERE r.id = :reviewId
               AND r.status IN :expectedStatuses
            """)
    int finalizeReview(
            @Param("reviewId") Long reviewId,
            @Param("status") AppEnums.ReviewStatus status,
            @Param("reviewResult") String reviewResult,
            @Param("score") Integer score,
            @Param("summary") String summary,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("expectedStatuses") Collection<AppEnums.ReviewStatus> expectedStatuses
    );

}
