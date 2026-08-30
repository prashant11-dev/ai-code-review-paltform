package com.aicode.code_review_platform.storage.exception;

/**
 * STEP 6.3 - an uploaded file was rejected before (or while) it was stored: empty, missing,
 * too large, an unsupported type, or a filename that is not a plain file name.
 *
 * <p>Extends RuntimeException so the existing {@code GlobalException} advice turns it into a
 * 400 carrying the message, the same way the other typed exceptions in this project behave.
 */
public class FileValidationException extends RuntimeException {

    public FileValidationException(String message) {
        super(message);
    }

}
