package com.aicode.code_review_platform.review.rabbitmq;

import com.aicode.code_review_platform.AI.dto.RepositoryReviewContext;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.AI.service.AIChunkReviewService;
import com.aicode.code_review_platform.AI.service.AIReviewService;
import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.service.ReviewAggregatorService;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReviewRepo;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import com.aicode.code_review_platform.review.github.service.ChunkGeneratorService;
import com.aicode.code_review_platform.review.github.service.CodeReaderService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryScannerService;
import com.aicode.code_review_platform.review.websocket.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

@Service
    public class ReviewConsumer {

    private static final Logger logger =
            LoggerFactory.getLogger(ReviewConsumer.class);

    @Autowired
    private CodeReviewRepo codeReviewRepo;

    @Autowired
    private AIReviewService aiReviewService;

    @Autowired
    private AIChunkReviewService aiChunkReviewService;

    @Autowired
    private ReviewAggregatorService reviewAggregatorService;

    @Autowired
    private RepositoryCloneService repositoryCloneService;

    @Autowired
    private RepositoryScannerService repositoryScannerService;

    @Autowired
    private CodeReaderService codeReaderService;

    @Autowired
    private ChunkGeneratorService chunkGeneratorService;

    @Autowired
    private  ObjectMapper objectMapper;

    @Autowired
    private NotificationService notificationService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void processReview(Long reviewId){

        logger.info("Processing review id: {}", reviewId);

        CodeReview review = codeReviewRepo.findById(reviewId).orElseThrow();

        review.setStatus(AppEnums.ReviewStatus.PROCESSING);
        codeReviewRepo.save(review);

        try {
            AIReviewResult result = review.getSourceType() == AppEnums.ReviewSourceType.GITHUB
                    ? processGithubReview(review)
                    : processStandardReview(review);

            review.setReviewResult(objectMapper.writeValueAsString(result));
            review.setStatus(AppEnums.ReviewStatus.COMPLETED);

            notificationService
                    .notifyReviewCompleted(
                            review.getId()
                    );

            codeReviewRepo.save(review);

            logger.info("Review completed for id: {}", reviewId);

        } catch (Exception e) {
            logger.error("AI review failed for id: {}, error: {}", reviewId, e.getMessage(), e);

            review.setStatus(AppEnums.ReviewStatus.FAILED);
            codeReviewRepo.save(review);
        }
    }

    private AIReviewResult processStandardReview(CodeReview review) {

        CodeFile file = CodeFile.builder()
                .fileName(review.getFileName() != null ? review.getFileName() : "submitted-code")
                .relativePath(review.getFileName() != null ? review.getFileName() : "submitted-code")
                .language(review.getLanguage())
                .content(review.getCode())
                .build();

        ReviewContext context = ReviewContext.builder()
                .files(List.of(file))
                .build();

        return aiReviewService.review(context);
    }

    private AIReviewResult processGithubReview(CodeReview review) {

        Path repositoryRoot = repositoryCloneService.cloneRepository(
                review.getRepositoryUrl(),
                review.getId()
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

            List<AIReviewResult> chunkResults = aiChunkReviewService.reviewChunks(chunks);

            RepositoryReviewContext context = RepositoryReviewContext.builder()
                    .review(review)
                    .chunks(chunks)
                    .chunkReviews(chunkResults)
                    .build();

            return reviewAggregatorService.aggregate(context);

        } catch (Exception e) {
            throw new RuntimeException("Failed to review GitHub repository", e);
        } finally {
            repositoryCloneService.deleteRepository(repositoryRoot);
        }
    }

}
