package com.aicode.code_review_platform.review.github.service;

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

    private static final Logger logger = LoggerFactory.getLogger(ChunkGeneratorServiceImpl.class);

    // Character budget per chunk (app.ai.max-chunk-size), sized to stay inside the model's
    // context window with room left for the prompt scaffolding and the response.
    @Value("${app.ai.max-chunk-size}")
    private int maxChunkSize;

    /**
     * PHASE 5 - packs files into size-capped chunks, each of which becomes one AI request.
     */
    @Override
    public List<CodeChunk> generateChunks(List<CodeFile> files) {

        List<CodeChunk> chunks = new ArrayList<>();

        // Running accumulator: files pile up here until adding one more would blow the budget.
        List<CodeFile> currentChunkFiles = new ArrayList<>();
        int currentSize = 0;

        for (CodeFile file : files) {
            int size = file.getContent().length();

            // Budget exceeded - seal the current chunk and start a fresh one. Files are never
            // split across chunks, so each chunk holds whole files the AI can reason about.
            // The isEmpty guard means a single oversized file still gets its own chunk rather
            // than producing an empty one.
            if (currentSize + size > maxChunkSize && !currentChunkFiles.isEmpty()) {
                chunks.add(buildChunk(chunks.size() + 1, currentChunkFiles, currentSize));

                currentChunkFiles = new ArrayList<>();
                currentSize = 0;
            }

            currentChunkFiles.add(file);
            currentSize += size;
        }

        // Flush the trailing partial chunk - the loop only seals a chunk when the next file
        // overflows it, so the last batch is still pending here.
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
        // chunkNumber is 1-based and becomes the stable identifier used to match a persisted
        // ReviewChunk row back to its in-memory chunk.
        return CodeChunk.builder()
                .chunkNumber(chunkNumber)
                .files(files)
                .totalCharacters(totalCharacters)
                .build();
    }
}
