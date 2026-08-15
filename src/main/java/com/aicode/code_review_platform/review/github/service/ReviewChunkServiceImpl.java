package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.ReviewChunkFile;
import com.aicode.code_review_platform.review.ReviewChunkFileRepository;
import com.aicode.code_review_platform.review.ReviewChunkRepository;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.mapper.ReviewChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewChunkServiceImpl implements ReviewChunkService {

    private final ReviewChunkRepository reviewChunkRepository;

    private final ReviewChunkFileRepository reviewChunkFileRepository;

    private final ReviewChunkMapper reviewChunkMapper;

    /**
     * Persists each chunk together with a snapshot of its files. Storing the file contents is what
     * makes the chunk self-contained, so an asynchronous consumer can build the prompt and run the
     * review from the database alone after the cloned repository has been deleted.
     */
    @Override
    @Transactional
    public List<ReviewChunk> createPendingChunks(CodeReview review, List<CodeChunk> chunks) {

        // PHASE 6 - turn each in-memory chunk into a database row. Status starts at PENDING:
        // queued, not yet picked up by a consumer.
        List<ReviewChunk> reviewChunks = chunks.stream()
                .map(chunk -> reviewChunkMapper.toEntity(review, chunk))
                .collect(Collectors.toList());

        // Single batch insert - after this returns, every chunk has an id, which is the only thing
        // the queue message needs to carry.
        List<ReviewChunk> saved = reviewChunkRepository.saveAll(reviewChunks);

        // Chunk rows and file rows are written in one transaction: a chunk without its files
        // could never be processed, so publishing it to the queue would guarantee a failure.
        List<ReviewChunkFile> files = collectFiles(saved, chunks);
        reviewChunkFileRepository.saveAll(files);

        log.info(
                "Persisted {} pending chunk(s) with {} file(s) for review id: {}",
                saved.size(),
                files.size(),
                review.getId()
        );

        return saved;
    }

    /**
     * Consumer side, step 1: a worker has picked the chunk off the queue and is about to call the
     * AI. Stamping startedAt here is what makes processing time measurable later.
     */
    @Override
    public ReviewChunk markStarted(ReviewChunk chunk) {

        chunk.setStatus(AppEnums.ReviewStatus.PROCESSING);
        chunk.setStartedAt(LocalDateTime.now());

        return reviewChunkRepository.save(chunk);
    }

    /**
     * Consumer side, terminal state: the AI returned a result for this chunk. The raw response is
     * stored so aggregation can run later without re-calling the model.
     */
    @Override
    public ReviewChunk markCompleted(ReviewChunk chunk, String prompt, String aiResponse) {

        chunk.setStatus(AppEnums.ReviewStatus.COMPLETED);
        chunk.setPrompt(prompt);
        chunk.setAiResponse(aiResponse);
        chunk.setCompletedAt(LocalDateTime.now());
        chunk.setProcessingTimeMs(processingTimeMs(chunk));

        return reviewChunkRepository.save(chunk);
    }

    /**
     * Consumer side, terminal state: the AI call failed for this chunk. Records the attempt with
     * no response, so a failed chunk is distinguishable from one still waiting in the queue.
     */
    @Override
    public ReviewChunk markFailed(ReviewChunk chunk, String prompt) {

        chunk.setStatus(AppEnums.ReviewStatus.FAILED);
        chunk.setPrompt(prompt);
        chunk.setCompletedAt(LocalDateTime.now());
        chunk.setProcessingTimeMs(processingTimeMs(chunk));

        return reviewChunkRepository.save(chunk);
    }

    private List<ReviewChunkFile> collectFiles(List<ReviewChunk> saved, List<CodeChunk> chunks) {

        // saveAll preserves the iteration order of its argument, so index i of the saved rows is
        // the persisted form of index i of the source chunks.
        List<ReviewChunkFile> files = new ArrayList<>();

        IntStream.range(0, saved.size())
                .forEach(index -> files.addAll(
                        reviewChunkMapper.toFileEntities(saved.get(index), chunks.get(index))
                ));

        return files;
    }

    private Long processingTimeMs(ReviewChunk chunk) {

        // Null when the chunk reached a terminal state without ever being marked started, e.g. it
        // failed before the AI call. Nothing meaningful to measure in that case.
        if (chunk.getStartedAt() == null) {
            return null;
        }

        return Duration.between(chunk.getStartedAt(), chunk.getCompletedAt()).toMillis();
    }

}
