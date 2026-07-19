package com.aicode.code_review_platform.review.github.exception;

public class RepositoryCloneException extends RuntimeException{

    public RepositoryCloneException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }

}
