package com.aicode.code_review_platform.storage.dto;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * STEP 6.3 - the result of storing one upload.
 *
 * <p>Carries both halves the caller needs: {@code storedFile} is what gets read for the review,
 * {@code uploadDirectory} is the one directory cleanup is allowed to remove. Returning the
 * directory explicitly keeps the caller from having to derive it from the file path.
 */
@Data
@Builder
public class StoredUpload {

    /** The per-upload directory (upload-&lt;uuid&gt;) - the cleanup target, never the shared root. */
    private Path uploadDirectory;

    /** The stored file itself, inside {@link #uploadDirectory}, under its original name. */
    private Path storedFile;

    /** The name the client sent, kept for processing and display. Never used to build the path. */
    private String originalFilename;

}
