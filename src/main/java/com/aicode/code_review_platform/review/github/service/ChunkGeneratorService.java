package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;

import java.util.List;

public interface ChunkGeneratorService {

    List<CodeChunk> generateChunks(
            List<CodeFile> files
    );

}
