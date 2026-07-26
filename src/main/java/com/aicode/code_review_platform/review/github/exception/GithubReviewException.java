package com.aicode.code_review_platform.review.github.exception;

/**
 * Raised when a GitHub repository could not be submitted for review.
 */
public class GithubReviewException extends RuntimeException {

    public GithubReviewException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }

}
