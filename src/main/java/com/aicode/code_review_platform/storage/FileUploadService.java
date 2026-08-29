package com.aicode.code_review_platform.storage;

import com.aicode.code_review_platform.storage.dto.StoredUpload;
import com.aicode.code_review_platform.storage.exception.FileValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * STEP 6.3 - stores an uploaded file as temporary review input and removes it again once the
 * review has taken what it needs.
 *
 * <p>Every upload gets its own directory under the configured upload root, so two concurrent
 * uploads can never collide on a filename and cleanup of one can never touch another. The shared
 * root itself (the uploads_data volume mount point) is created once and never deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadService {

    /**
     * STEP 6.3 - every directory this service creates carries this prefix, and cleanup refuses to
     * touch anything that does not. It is the marker that says "this directory is ours to delete".
     */
    private static final String UPLOAD_DIRECTORY_PREFIX = "upload-";

    @Autowired
    private FileStorageConfig fileStorageConfig;

    /**
     * Stores the upload under a freshly created upload-&lt;uuid&gt; directory and returns both the
     * directory (the cleanup target) and the file inside it.
     *
     * <p>The original filename is preserved as the file name inside that directory, but never
     * decides the directory itself, so it cannot collide with another upload or escape the root.
     */
    public StoredUpload storeFile(MultipartFile file) throws IOException {

        Path baseDirectory = baseDirectory();

        // Declared outside the try so a failure after the directory exists can still remove it.
        Path uploadDirectory = null;

        String originalFilename = file.getOriginalFilename();

        try {
            // The shared parent (/app/uploads in the container, backed by the uploads_data
            // volume). Created once, and never deleted by cleanup.
            Files.createDirectories(baseDirectory);

            // upload-<uuid>: unique per upload, so concurrent uploads of the same filename each
            // get their own directory. createDirectory (not createDirectories) fails instead of
            // reusing a directory that already exists.
            uploadDirectory = baseDirectory.resolve(UPLOAD_DIRECTORY_PREFIX + UUID.randomUUID());
            Files.createDirectory(uploadDirectory);

            Path target = resolveInsideUploadDirectory(uploadDirectory, originalFilename);

            // No REPLACE_EXISTING: the directory was created empty a line ago, so anything already
            // sitting at this path would mean something is badly wrong rather than something to
            // silently overwrite.
            Files.copy(file.getInputStream(), target);

            log.info(
                    "Upload stored: originalFilename={}, path={}, size={} byte(s)",
                    originalFilename,
                    target,
                    file.getSize()
            );

            return StoredUpload.builder()
                    .uploadDirectory(uploadDirectory)
                    .storedFile(target)
                    .originalFilename(originalFilename)
                    .build();

        } catch (IOException | RuntimeException e) {
            log.error("Failed to store upload {}: {}", originalFilename, e.getMessage(), e);

            // Clean up the partial upload ourselves: the caller never received a StoredUpload, so
            // its own finally block has nothing to delete.
            deleteUpload(uploadDirectory);

            throw e;
        }
    }

    /**
     * STEP 6.3 - removes one upload directory and everything in it. Best-effort and null-safe,
     * because it runs from a finally block that may execute after a failed store.
     *
     * <p>Never throws: a leftover upload is a disk problem, and must not replace the outcome of
     * the review that triggered the cleanup. Failures are logged rather than swallowed.
     */
    public void deleteUpload(Path uploadDirectory) {

        // Called from a finally block, so it has to tolerate a store that never happened.
        if (uploadDirectory == null) {
            log.debug("Review upload cleanup skipped: no upload directory was created");
            return;
        }

        Path target = uploadDirectory.toAbsolutePath().normalize();

        // STEP 6.3 - the safety gate. Everything past this point deletes recursively, so a path
        // that is not one of our own per-upload directories must never get through.
        if (!isManagedUploadDirectory(target)) {
            log.error(
                    "Review upload cleanup refused for {}: not a per-upload directory directly under {}",
                    target,
                    baseDirectory()
            );
            return;
        }

        if (!Files.exists(target)) {
            log.debug("Review upload cleanup skipped for {}: directory no longer exists", target);
            return;
        }

        log.info("Review upload cleanup started for {}", target);

        try (var walk = Files.walk(target)) {

            // Reverse order puts children before their parents, which is what Files.delete needs -
            // it will not remove a directory that still has contents. The upload directory itself
            // sorts last, so it goes only once everything inside it is gone.
            List<Path> failures = walk.sorted(Comparator.reverseOrder())
                    .filter(entry -> !deleteQuietly(entry))
                    .toList();

            if (failures.isEmpty()) {
                log.info("Review upload cleanup completed for {}", target);
            } else {
                // Not rethrown: a leftover upload directory is a disk problem, not a review failure.
                log.error(
                        "Review upload cleanup incomplete for {}: {} entry/entries could not be deleted, first was {}",
                        target,
                        failures.size(),
                        failures.getFirst()
                );
            }

        } catch (IOException | RuntimeException e) {
            // Cleanup is best-effort and must never change the outcome of the review that
            // triggered it, so the failure is logged in full instead of propagated.
            log.error("Review upload cleanup failed for {}: {}", target, e.getMessage(), e);
        }
    }

    /**
     * STEP 6.3 - the traversal gate. The original filename is reduced to its last path segment and
     * the result is resolved against the upload directory, then normalized and checked: whatever
     * the client sent, the file lands directly inside its own upload directory or not at all.
     *
     * <p>Both separators are stripped explicitly rather than through {@code Paths.get}, because a
     * backslash is a separator on Windows but an ordinary filename character on Linux - the
     * container would otherwise treat a backslash-separated traversal as a plain file name.
     */
    private Path resolveInsideUploadDirectory(Path uploadDirectory, String originalFilename) {

        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FileValidationException("File name is required");
        }

        String fileName = originalFilename.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);

        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new FileValidationException("Invalid file name: " + originalFilename);
        }

        Path target = uploadDirectory.resolve(fileName).normalize();

        // Belt and braces: after normalization the file must still sit directly in its own upload
        // directory. Nothing above should be able to produce anything else, which is exactly why
        // it is worth asserting before a write.
        if (!uploadDirectory.equals(target.getParent())) {
            throw new FileValidationException("Invalid file name: " + originalFilename);
        }

        return target;
    }

    /**
     * STEP 6.3 - a path is deletable only when it is a direct child of the configured upload
     * directory and carries the prefix this service stores into. That rules out the parent itself
     * (/app/uploads), anything above it (/app, /), anything outside it entirely, nested paths
     * inside an upload, and any unrelated directory that happens to sit next to ours.
     */
    private boolean isManagedUploadDirectory(Path candidate) {

        // A symlink dropped into the upload directory could otherwise point cleanup at an
        // unrelated tree. Files.walk does not follow links, but the top-level entry is checked
        // explicitly so it is never even entered.
        if (Files.isSymbolicLink(candidate)) {
            return false;
        }

        return baseDirectory().equals(candidate.getParent())
                && candidate.getFileName().toString().startsWith(UPLOAD_DIRECTORY_PREFIX);
    }

    /** The configured shared upload root, absolute and normalized. Never deleted. */
    private Path baseDirectory() {
        return Paths.get(fileStorageConfig.getUploadDir()).toAbsolutePath().normalize();
    }

    /** Deletes a single entry, reporting whether it is gone. Never throws. */
    private boolean deleteQuietly(Path path) {

        File file = path.toFile();

        // A read-only upload would otherwise fail to delete on Windows.
        if (!file.canWrite()) {
            file.setWritable(true);
        }

        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
            return false;
        }
    }

}
