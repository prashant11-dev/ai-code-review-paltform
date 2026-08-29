package com.aicode.code_review_platform.review.github;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.CodeReviewRepo;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.review.github.dto.ReviewSubmissionResponse;
import com.aicode.code_review_platform.review.github.exception.GithubReviewException;
import com.aicode.code_review_platform.review.github.service.ChunkGeneratorService;
import com.aicode.code_review_platform.review.github.service.CodeReaderService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryScannerService;
import com.aicode.code_review_platform.review.github.service.ReviewChunkService;
import com.aicode.code_review_platform.review.rabbitmq.ChunkProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates GitHub repository submission: clone, scan, chunk, persist and publish.
 *
 * <p>AI review happens asynchronously in the consumer of
 * {@link com.aicode.code_review_platform.review.rabbitmq.RabbitMQConfig#CHUNK_REVIEW_QUEUE}, so
 * this service returns as soon as every chunk has been handed to the broker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GithubReviewService {

    private final CodeReviewRepo codeReviewRepo;

    private final RepositoryCloneService repositoryCloneService;

    private final RepositoryScannerService repositoryScannerService;

    private final CodeReaderService codeReaderService;

    private final ChunkGeneratorService chunkGeneratorService;

    private final ReviewChunkService reviewChunkService;

    private final ChunkProducerService chunkProducerService;

    public ReviewSubmissionResponse submitGithubReview(GithubReviewRequest request, User user) {

        // PHASE 1 - Create the review row first, so the caller has an id to poll with even if
        // every later phase fails. PENDING means "accepted, nothing processed yet".
        CodeReview review = codeReviewRepo.save(
                CodeReview.builder()
                        .sourceType(AppEnums.ReviewSourceType.GITHUB)
                        .repositoryUrl(request.getRepositoryUrl())
                        .status(AppEnums.ReviewStatus.PENDING)
                        .user(user)
                        .build()
        );

        log.info(
                "Starting GitHub review for reviewId={}, repository={}",
                review.getId(),
                review.getRepositoryUrl()
        );

        // Declared outside the try so the finally block can clean up even if the clone itself blew up.
        Path repositoryRoot = null;

        try {
            // PHASE 2 - Clone the repository into a temp directory named after the review id.
            // This is the only phase that touches the network; everything after it works on local files.
            repositoryRoot = repositoryCloneService.cloneRepository(review.getRepositoryUrl(), review.getId());
            log.info("Cloned repository for reviewId={} at path={}", review.getId(), repositoryRoot);

            // PHASE 3-5 - Turn the cloned directory into in-memory chunks (scan -> read -> pack).
            List<CodeChunk> chunks = generateChunks(repositoryRoot, review.getId());

            // PHASE 6 - Persist one review_chunks row per chunk, each carrying its own pre-built
            // prompt. This is the durability boundary: after this line the work survives a restart
            // and no longer depends on the cloned files, which are about to be deleted.
            List<ReviewChunk> reviewChunks = reviewChunkService.createPendingChunks(review, chunks);
            log.info("Saved {} ReviewChunks for reviewId={}", reviewChunks.size(), review.getId());

            // PHASE 7 - Hand every chunk to RabbitMQ. From here the AI work happens in the consumer,
            // off the request thread.
            publishChunks(reviewChunks, review.getId());

            // PHASE 8 - Everything is queued, so the review moves from PENDING to PROCESSING.
            // It will reach COMPLETED only once the consumer has finished all chunks and aggregated them.
            review.setStatus(AppEnums.ReviewStatus.PROCESSING);
            CodeReview processing = codeReviewRepo.save(review);

            log.info("GitHub review submitted successfully for reviewId={}", review.getId());

            // PHASE 9 - Reply with the review id and its current (still unfinished) state.
            return mapToResponse(processing);

        } catch (Exception e) {
            // Any phase above failing means nothing usable was queued: mark the review FAILED so
            // the client stops polling, and rethrow as a typed exception for the controller advice.
            log.error(
                    "GitHub review submission failed for reviewId={}: {}",
                    review.getId(),
                    e.getMessage(),
                    e
            );

            review.setStatus(AppEnums.ReviewStatus.FAILED);
            codeReviewRepo.save(review);

            throw new GithubReviewException("Failed to submit GitHub repository for review", e);

        } finally {
            // Always remove the clone - success or failure. The chunks in the database already hold
            // everything the consumer needs, so keeping the checkout around would only waste disk.
            //
            // STEP 6.2 - deleteRepository never throws, so a cleanup problem is logged rather than
            // turning an accepted submission into a failed one. A null root (the clone itself
            // failed) is a no-op, since the clone already removed its own partial directory.
            repositoryCloneService.deleteRepository(repositoryRoot);
        }
    }

    private List<CodeChunk> generateChunks(Path repositoryRoot, Long reviewId) throws IOException {

        // PHASE 3 - Walk the clone and keep only reviewable source files, dropping build output,
        // .git internals and dependency folders. Returns paths only, nothing is read yet.
        List<Path> paths = repositoryScannerService.scanRepository(repositoryRoot);
        log.info("Scanned repository for reviewId={}, found {} candidate file(s)", reviewId, paths.size());

        // PHASE 4 - Load the contents of those paths into memory as CodeFile objects, each tagged
        // with its language and repo-relative path. Unreadable files are skipped, not fatal.
        List<CodeFile> files = codeReaderService.readFiles(paths, repositoryRoot);
        log.info("Read {} file(s) for reviewId={}", files.size(), reviewId);

        // PHASE 5 - Pack the files into chunks that each fit under the model's size budget,
        // because a whole repository will not fit into one AI request.
        List<CodeChunk> chunks = chunkGeneratorService.generateChunks(files);
        log.info("Generated {} chunks for reviewId={}", chunks.size(), reviewId);

        // Nothing reviewable in the repository - fail loudly here rather than queueing zero chunks
        // and leaving the review stuck in PROCESSING forever.
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No supported source files found in repository (supported extensions: .java, .js, .ts, .jsx, .tsx, .py)"
            );
        }

        return chunks;
    }

    private void publishChunks(List<ReviewChunk> reviewChunks, Long reviewId) {

        log.info("Publishing {} ReviewChunks for reviewId={}", reviewChunks.size(), reviewId);

        // One message per chunk, so chunks are reviewed independently and can be spread across
        // however many consumers are running. A failure part-way propagates and fails the submission.
        reviewChunks.forEach(chunkProducerService::publish);
    }

    private ReviewSubmissionResponse mapToResponse(CodeReview review) {

        // Shapes the entity into the API response. reviewResult is still null at submission time -
        // it gets filled in later, once the consumer aggregates the per-chunk results.
        return ReviewSubmissionResponse.builder()
                .id(review.getId())
                .repositoryUrl(review.getRepositoryUrl())
                .reviewResult(review.getReviewResult())
                .status(review.getStatus())
                .createdAt(review.getCreatedAt())
                .build();
    }

}
