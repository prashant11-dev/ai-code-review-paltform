package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.review.dto.CodeReviewRequest;
import com.aicode.code_review_platform.review.dto.CodeReviewResponse;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.storage.FileUploadService;
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
import java.nio.file.Path;

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

    public Long uploadReview(MultipartFile file, User user) throws IOException {

        fileValidator.validate(file);

        Path path = fileUploadService.saveFile(file);

        String code = Files.readString(path);

        String originalFilename = file.getOriginalFilename();
        String language = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1)
                : "unknown";

        CodeReview review = CodeReview.builder().sourceType(AppEnums.ReviewSourceType.FILE).fileName(originalFilename).filePath(path.toString()).fileSize(file.getSize()).language(language).code(code).status(AppEnums.ReviewStatus.PENDING).user(user).build();

        CodeReview saved = codeReviewRepo.save(review);

        reviewProducer.sendReview(saved.getId());

        return saved.getId();
    }

}
