package com.aicode.code_review_platform.review.github;

import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.RepositoryReviewContext;
import com.aicode.code_review_platform.AI.service.AIChunkReviewService;
import com.aicode.code_review_platform.AI.service.ReviewAggregatorService;
import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.CodeReviewRepo;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.review.github.dto.ReviewSubmissionResponse;
import com.aicode.code_review_platform.review.github.service.ChunkGeneratorService;
import com.aicode.code_review_platform.review.github.service.CodeReaderService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryScannerService;
import com.aicode.code_review_platform.review.websocket.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

@Service
public class GithubReviewService {

    private static final Logger logger = LoggerFactory.getLogger(GithubReviewService.class);

    @Autowired
    private CodeReviewRepo codeReviewRepo;

    @Autowired
    private RepositoryCloneService repositoryCloneService;

    @Autowired
    private RepositoryScannerService repositoryScannerService;

    @Autowired
    private CodeReaderService codeReaderService;

    @Autowired
    private ChunkGeneratorService chunkGeneratorService;

    @Autowired
    private AIChunkReviewService aiChunkReviewService;

    @Autowired
    private ReviewAggregatorService reviewAggregatorService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    public ReviewSubmissionResponse submitGithubReview(
            GithubReviewRequest request,
            User user
    ) {

        logger.info("Submitting GitHub repository review for user: {}, repository: {}", user.getEmail(), request.getRepositoryUrl());

        CodeReview review = CodeReview.builder()
                .sourceType(AppEnums.ReviewSourceType.GITHUB)
                .repositoryUrl(request.getRepositoryUrl())
                .status(AppEnums.ReviewStatus.PENDING)
                .user(user)
                .build();

        CodeReview saved = codeReviewRepo.save(review);

        saved.setStatus(AppEnums.ReviewStatus.PROCESSING);
        codeReviewRepo.save(saved);

        Path repositoryRoot = repositoryCloneService.cloneRepository(
                saved.getRepositoryUrl(),
                saved.getId()
        );

        try {
            List<Path> paths = repositoryScannerService.scanRepository(repositoryRoot);
            List<CodeFile> files = codeReaderService.readFiles(paths, repositoryRoot);
            List<CodeChunk> chunks = chunkGeneratorService.generateChunks(files);

            if (chunks.isEmpty()) {
                throw new IllegalArgumentException(
                        "No supported source files found in repository (supported extensions: .java, .js, .ts, .jsx, .tsx, .py)"
                );
            }

            List<AIReviewResult> chunkReviews = aiChunkReviewService.reviewChunks(chunks);

            RepositoryReviewContext context = RepositoryReviewContext.builder()
                    .review(saved)
                    .chunks(chunks)
                    .chunkReviews(chunkReviews)
                    .build();

            AIReviewResult result = reviewAggregatorService.aggregate(context);

            saved.setReviewResult(objectMapper.writeValueAsString(result));
            saved.setStatus(AppEnums.ReviewStatus.COMPLETED);
            codeReviewRepo.save(saved);

            notificationService.notifyReviewCompleted(saved.getId());

            logger.info("GitHub review completed for id: {}", saved.getId());

            return mapToResponse(saved);

        } catch (Exception e) {
            logger.error("GitHub review failed for id: {}, error: {}", saved.getId(), e.getMessage(), e);

            saved.setStatus(AppEnums.ReviewStatus.FAILED);
            codeReviewRepo.save(saved);

            throw new RuntimeException("Failed to review GitHub repository", e);

        } finally {
            repositoryCloneService.deleteRepository(repositoryRoot);
        }
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
