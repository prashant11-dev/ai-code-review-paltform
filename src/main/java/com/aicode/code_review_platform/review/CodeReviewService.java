package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.review.dto.CodeReviewRequest;
import com.aicode.code_review_platform.review.dto.CodeReviewResponse;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.storage.FileUploadService;
import com.aicode.code_review_platform.storage.dto.StoredUpload;
import com.aicode.code_review_platform.review.rabbitmq.ReviewProducer;
import com.aicode.code_review_platform.common.utils.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;

@Service
public class CodeReviewService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewService.class);

    @Autowired
    private CodeReviewRepo codeReviewRepo;

    @Autowired
    private ReviewProducer reviewProducer;

    @Autowired
    private FileValidator fileValidator;

    @Autowired
    private FileUploadService fileUploadService;

    public CodeReviewResponse submitReview(CodeReviewRequest request, User user) {

        logger.info("Submitting code review for user: {}", user.getEmail());

        CodeReview review = CodeReview.builder().sourceType(AppEnums.ReviewSourceType.TEXT).language(request.getLanguage()).code(request.getCode()).status(AppEnums.ReviewStatus.PENDING).user(user).build();

        CodeReview saved = codeReviewRepo.save(review);
        reviewProducer.sendReview(saved.getId());

        logger.info("Review saved with id: {}", saved.getId());

        return mapToResponse(saved);
    }

    public CodeReviewResponse submitGithubReview(GithubReviewRequest request, User user) {

        logger.info("Submitting GitHub repository review for user: {}, repository: {}", user.getEmail(), request.getRepositoryUrl());

        CodeReview review = CodeReview.builder().sourceType(AppEnums.ReviewSourceType.GITHUB).repositoryUrl(request.getRepositoryUrl()).status(AppEnums.ReviewStatus.PENDING).user(user).build();

        CodeReview saved = codeReviewRepo.save(review);
        reviewProducer.sendReview(saved.getId());

        logger.info("GitHub review saved with id: {}", saved.getId());

        return mapToResponse(saved);
    }

    public CodeReviewResponse getReview(Long id) {

        CodeReview review = codeReviewRepo.findById(id).orElseThrow(() -> new RuntimeException("Review not found"));

        return mapToResponse(review);
    }


    private CodeReviewResponse mapToResponse(CodeReview review) {

        return CodeReviewResponse.builder().id(review.getId()).language(review.getLanguage()).code(review.getCode()).reviewResult(review.getReviewResult()).status(review.getStatus()).createdAt(review.getCreatedAt()).sourceType(review.getSourceType()).repositoryUrl(review.getRepositoryUrl()).build();
    }

    /**
     * STEP 6.3 - accepts an uploaded source file as temporary review input.
     *
     * <p>The upload is validated, stored under its own directory on the uploads volume, read into
     * the review row, and then deleted again. Persisting the code is the durability boundary: from
     * that point the queued review no longer depends on the file, so keeping the upload around
     * would only consume volume space. Cleanup therefore runs in a finally block - success,
     * failure or unexpected exception alike.
     */
    public Long uploadReview(MultipartFile file, User user) throws IOException {

        logger.info(
                "Upload received from user: {}, fileName: {}, size: {} byte(s)",
                user.getEmail(),
                file != null ? file.getOriginalFilename() : null,
                file != null ? file.getSize() : 0
        );

        // Validated before anything is written, so a rejected upload never reaches the volume.
        fileValidator.validate(file);

        StoredUpload upload = fileUploadService.storeFile(file);

        // Declared outside the try so the cleanup log can name the review even when the failure
        // happened after the row was saved.
        Long reviewId = null;

        try {
            String code = Files.readString(upload.getStoredFile());

            String originalFilename = upload.getOriginalFilename();
            String language = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1)
                    : "unknown";

            CodeReview review = CodeReview.builder().sourceType(AppEnums.ReviewSourceType.FILE).fileName(originalFilename).filePath(upload.getStoredFile().toString()).fileSize(file.getSize()).language(language).code(code).status(AppEnums.ReviewStatus.PENDING).user(user).build();

            CodeReview saved = codeReviewRepo.save(review);
            reviewId = saved.getId();

            reviewProducer.sendReview(reviewId);

            logger.info("Upload review saved with id: {}", reviewId);

            return reviewId;

        } finally {
            // Always remove the upload - success or failure, and whatever failed (reading the
            // file, saving the row, publishing the message). deleteUpload never throws, so a
            // cleanup problem is logged rather than replacing the original outcome.
            logger.info(
                    "Review upload cleanup requested for reviewId: {}, path: {}",
                    reviewId,
                    upload.getUploadDirectory()
            );

            fileUploadService.deleteUpload(upload.getUploadDirectory());
        }
    }

}
