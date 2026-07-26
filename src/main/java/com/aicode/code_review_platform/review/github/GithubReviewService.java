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

        Path repositoryRoot = null;

        try {
            repositoryRoot = repositoryCloneService.cloneRepository(review.getRepositoryUrl(), review.getId());
            log.info("Cloned repository for reviewId={} at path={}", review.getId(), repositoryRoot);

            List<CodeChunk> chunks = generateChunks(repositoryRoot, review.getId());

            List<ReviewChunk> reviewChunks = reviewChunkService.createPendingChunks(review, chunks);
            log.info("Saved {} ReviewChunks for reviewId={}", reviewChunks.size(), review.getId());

            publishChunks(reviewChunks, review.getId());

            review.setStatus(AppEnums.ReviewStatus.PROCESSING);
            CodeReview processing = codeReviewRepo.save(review);

            log.info("GitHub review submitted successfully for reviewId={}", review.getId());

            return mapToResponse(processing);

        } catch (Exception e) {
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
            repositoryCloneService.deleteRepository(repositoryRoot);
        }
    }

    private List<CodeChunk> generateChunks(Path repositoryRoot, Long reviewId) throws IOException {

        List<Path> paths = repositoryScannerService.scanRepository(repositoryRoot);
        log.info("Scanned repository for reviewId={}, found {} candidate file(s)", reviewId, paths.size());

        List<CodeFile> files = codeReaderService.readFiles(paths, repositoryRoot);
        log.info("Read {} file(s) for reviewId={}", files.size(), reviewId);

        List<CodeChunk> chunks = chunkGeneratorService.generateChunks(files);
        log.info("Generated {} chunks for reviewId={}", chunks.size(), reviewId);

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(
                    "No supported source files found in repository (supported extensions: .java, .js, .ts, .jsx, .tsx, .py)"
            );
        }

        return chunks;
    }

    private void publishChunks(List<ReviewChunk> reviewChunks, Long reviewId) {

        log.info("Publishing {} ReviewChunks for reviewId={}", reviewChunks.size(), reviewId);

        reviewChunks.forEach(chunkProducerService::publish);
    }

    private ReviewSubmissionResponse mapToResponse(CodeReview review) {

        return ReviewSubmissionResponse.builder()
                .id(review.getId())
                .repositoryUrl(review.getRepositoryUrl())
                .reviewResult(review.getReviewResult())
                .status(review.getStatus())
                .createdAt(review.getCreatedAt())
                .build();
    }

}
