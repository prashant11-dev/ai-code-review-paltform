package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.enums.AppEnums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewChunkRepository extends JpaRepository<ReviewChunk, Long> {

    List<ReviewChunk> findByReviewIdOrderByChunkNumberAsc(Long reviewId);

    List<ReviewChunk> findByReviewIdAndStatus(Long reviewId, AppEnums.ReviewStatus status);

    Optional<ReviewChunk> findByReviewIdAndChunkNumber(Long reviewId, Integer chunkNumber);

}
