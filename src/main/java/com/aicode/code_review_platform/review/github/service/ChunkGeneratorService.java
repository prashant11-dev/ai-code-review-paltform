package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;

import java.util.List;

public interface ChunkGeneratorService {

    /**
     * PHASE 5 - groups files into chunks that each stay under the configured character budget,
     * since a whole repository cannot fit in a single AI request. Files are never split.
     */
    List<CodeChunk> generateChunks(
            List<CodeFile> files
    );

}
