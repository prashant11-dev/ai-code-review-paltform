package com.aicode.code_review_platform.common.utils;

import com.aicode.code_review_platform.storage.FileStorageConfig;
import com.aicode.code_review_platform.storage.exception.FileValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * STEP 6.3 - the gate an upload passes before anything is written to disk.
 *
 * <p>Everything here is checked against the request alone, so a rejected upload never reaches the
 * uploads volume at all.
 */
@Component
public class FileValidator {

    /**
     * The languages the review pipeline can actually read. Deliberately the same set the
     * repository scanner accepts, so an uploaded file and a cloned repository agree on what counts
     * as reviewable source.
     */
    private static final Set<String> ALLOWED = Set.of("java", "js", "ts", "jsx", "tsx", "py");

    @Autowired
    private FileStorageConfig fileStorageConfig;

    public void validate(MultipartFile file) {

        // No part bound to the request at all, or a part with no content: both would otherwise
        // produce an empty review that can never say anything useful.
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("Uploaded file is missing or empty");
        }

        String originalFilename = file.getOriginalFilename();

        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FileValidationException("Uploaded file name is required");
        }

        validateFileName(originalFilename);
        validateSize(file, originalFilename);
        validateExtension(originalFilename);
    }

    /**
     * STEP 6.3 - the filename has to be a plain file name, not a path. Anything carrying a
     * separator, a parent reference or a Windows stream/drive separator is rejected outright
     * rather than sanitized, so the client gets told its request was wrong instead of quietly
     * having its filename rewritten.
     */
    private void validateFileName(String originalFilename) {

        // Both separators, whatever the host OS thinks: a backslash is an ordinary filename
        // character on Linux, so the container would otherwise accept a Windows-style path here.
        boolean unsafe = originalFilename.contains("/")
                || originalFilename.contains("\\")
                || originalFilename.contains("..")
                || originalFilename.contains(":")
                || originalFilename.contains("\0")
                || originalFilename.equals(".");

        if (unsafe) {
            throw new FileValidationException(
                    "Invalid file name: " + originalFilename + " (path components are not allowed)"
            );
        }
    }

    /**
     * The same ceiling Spring applies to the multipart request itself. Spring normally rejects an
     * oversized upload before the controller runs; this keeps the rule enforced for any other
     * caller and states the limit in the message rather than as a generic 500.
     */
    private void validateSize(MultipartFile file, String originalFilename) {

        long maxBytes = fileStorageConfig.getMaxFileSize().toBytes();

        if (file.getSize() > maxBytes) {
            throw new FileValidationException(
                    "Uploaded file " + originalFilename + " is " + file.getSize()
                            + " bytes, which exceeds the limit of " + maxBytes + " bytes"
            );
        }
    }

    private void validateExtension(String originalFilename) {

        int lastDot = originalFilename.lastIndexOf('.');

        // No dot at all, or a trailing dot: there is no extension to match, so there is no way to
        // tell the AI what language it is looking at.
        if (lastDot < 0 || lastDot == originalFilename.length() - 1) {
            throw new FileValidationException("Unsupported file type: " + originalFilename);
        }

        String extension = originalFilename.substring(lastDot + 1).toLowerCase(Locale.ROOT);

        if (!ALLOWED.contains(extension)) {
            throw new FileValidationException("Unsupported file type: " + extension);
        }
    }

}
