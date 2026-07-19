package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.CodeReviewService;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkGeneratorServiceImpl implements ChunkGeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewService.class);

    @Value("${app.ai.max-chunk-size}")
    private int maxChunkSize;

    @Override
    public List<CodeChunk> generateChunks(List<CodeFile> files) {

        List<CodeChunk> chunks = new ArrayList<>();

        List<CodeFile> currentChunkFiles = new ArrayList<>();
        int currentSize = 0;

        for (CodeFile file : files) {
            int size = file.getContent().length();

            if (currentSize + size > maxChunkSize && !currentChunkFiles.isEmpty()) {
                chunks.add(buildChunk(chunks.size() + 1, currentChunkFiles, currentSize));

                currentChunkFiles = new ArrayList<>();
                currentSize = 0;
            }

            currentChunkFiles.add(file);
            currentSize += size;
        }

        if (!currentChunkFiles.isEmpty()) {
            chunks.add(buildChunk(chunks.size() + 1, currentChunkFiles, currentSize));
        }

        logger.info(
                "Generated {} chunks from {} files",
                chunks.size(),
                files.size()
        );
        return chunks;
    }

    private CodeChunk buildChunk(int chunkNumber, List<CodeFile> files, int totalCharacters) {
        return CodeChunk.builder()
                .chunkNumber(chunkNumber)
                .files(files)
                .totalCharacters(totalCharacters)
                .build();
    }
}
