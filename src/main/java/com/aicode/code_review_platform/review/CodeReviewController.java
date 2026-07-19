package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.review.dto.CodeReviewRequest;
import com.aicode.code_review_platform.review.dto.CodeReviewResponse;
import com.aicode.code_review_platform.review.github.GithubReviewService;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.common.ApiResponse;
import com.aicode.code_review_platform.review.github.dto.ReviewSubmissionResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/reviews")
public class CodeReviewController {

    @Autowired
    CodeReviewService codeReviewService;

    @Autowired
    private GithubReviewService githubReviewService;

    @PostMapping
    public ResponseEntity<ApiResponse<CodeReviewResponse>> submitReview(@Valid @RequestBody CodeReviewRequest request, Authentication authentication) {

        User user = (User) authentication.getPrincipal();

        CodeReviewResponse response = codeReviewService.submitReview(request, user);

        return ResponseEntity.ok(new ApiResponse<>(true, "Review submitted", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CodeReviewResponse>> getReview(@PathVariable Long id) {

        return ResponseEntity.ok(new ApiResponse<>(true, "Review fetched", codeReviewService.getReview(id)));
    }

    @PostMapping("/github")
    public ResponseEntity<ApiResponse<ReviewSubmissionResponse>> submitGithubReview(
            @Valid @RequestBody GithubReviewRequest request,
            Authentication authentication
    ) {

        User user = (User) authentication.getPrincipal();

        ReviewSubmissionResponse response = githubReviewService.submitGithubReview(request, user);

        return ResponseEntity.ok(new ApiResponse<>(true, "GitHub review submitted", response));
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Long>> uploadReview(@RequestParam("file") MultipartFile file, Authentication authentication) throws Exception {

        User user = (User) authentication.getPrincipal();

        Long reviewId = codeReviewService.uploadReview(file, user);

        return ResponseEntity.ok(new ApiResponse<>(true, "Review submitted", reviewId));
    }

}
