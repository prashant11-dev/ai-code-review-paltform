package com.aicode.code_review_platform.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewChunkFileRepository extends JpaRepository<ReviewChunkFile, Long> {

    /**
     * Loads the files of one chunk in their original packing order, which is what the prompt
     * builder needs to produce a deterministic prompt.
     */
    List<ReviewChunkFile> findByChunkIdOrderByFileOrderAsc(Long chunkId);

}
