package com.aicode.code_review_platform.storage;

import com.aicode.code_review_platform.storage.dto.StoredUpload;
import com.aicode.code_review_platform.storage.exception.FileValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STEP 6.3 - unit tests for the uploaded file lifecycle.
 *
 * <p>Nothing is mocked: the tests run against a real temp directory tree, because what is being
 * verified is which files survive a cleanup and which do not.
 */
class FileUploadServiceTest {

    /** Stands in for /app/uploads - the shared root that must always survive. */
    @TempDir
    Path uploads;

    /** Sibling of the upload root, used to prove cleanup will not reach outside of it. */
    @TempDir
    Path unrelated;

    private FileUploadService fileUploadService;

    @BeforeEach
    void setUp() {

        FileStorageConfig fileStorageConfig = new FileStorageConfig();
        fileStorageConfig.setUploadDir(uploads.toString());
        fileStorageConfig.setMaxFileSize(DataSize.ofMegabytes(10));

        fileUploadService = new FileUploadService();
        ReflectionTestUtils.setField(fileUploadService, "fileStorageConfig", fileStorageConfig);
    }

    // ---------------------------------------------------------------------------------------
    // Storing
    // ---------------------------------------------------------------------------------------

    @Test
    void storeWritesTheFileUnderItsOwnDirectoryKeepingTheOriginalName() throws Exception {

        StoredUpload upload = fileUploadService.storeFile(
                multipartFile("UserService.java", "class UserService {}")
        );

        // The physical directory is generated, the file inside it keeps the name the client sent.
        assertThat(upload.getUploadDirectory().getParent()).isEqualTo(uploads);
        assertThat(upload.getUploadDirectory().getFileName().toString()).startsWith("upload-");
        assertThat(upload.getStoredFile().getFileName().toString()).isEqualTo("UserService.java");
        assertThat(upload.getStoredFile().getParent()).isEqualTo(upload.getUploadDirectory());
        assertThat(upload.getOriginalFilename()).isEqualTo("UserService.java");

        assertThat(upload.getStoredFile()).exists();
        assertThat(Files.readString(upload.getStoredFile())).isEqualTo("class UserService {}");
    }

    @Test
    void everyUploadGetsItsOwnDirectoryEvenForTheSameFileName() throws Exception {

        // The concurrency case: two users uploading Main.java at the same time. Neither may see
        // or overwrite the other one.
        StoredUpload first = fileUploadService.storeFile(multipartFile("Main.java", "first"));
        StoredUpload second = fileUploadService.storeFile(multipartFile("Main.java", "second"));
        StoredUpload third = fileUploadService.storeFile(multipartFile("Main.java", "third"));

        assertThat(List.of(
                first.getUploadDirectory(),
                second.getUploadDirectory(),
                third.getUploadDirectory()
        )).doesNotHaveDuplicates();

        assertThat(Files.readString(first.getStoredFile())).isEqualTo("first");
        assertThat(Files.readString(second.getStoredFile())).isEqualTo("second");
        assertThat(Files.readString(third.getStoredFile())).isEqualTo("third");

        try (var entries = Files.list(uploads)) {
            assertThat(entries).hasSize(3);
        }
    }

    @Test
    void deletingOneUploadLeavesConcurrentUploadsUntouched() throws Exception {

        StoredUpload a = fileUploadService.storeFile(multipartFile("A.java", "a"));
        StoredUpload b = fileUploadService.storeFile(multipartFile("B.java", "b"));
        StoredUpload c = fileUploadService.storeFile(multipartFile("C.java", "c"));

        fileUploadService.deleteUpload(b.getUploadDirectory());

        assertThat(b.getUploadDirectory()).doesNotExist();
        assertThat(a.getStoredFile()).exists();
        assertThat(c.getStoredFile()).exists();
        assertThat(uploads).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Path traversal
    // ---------------------------------------------------------------------------------------

    @Test
    void storeCannotBeMadeToWriteOutsideTheUploadRoot() throws Exception {

        // Reaches the storage layer only if validation was bypassed - the filename is reduced to
        // its last segment, so the write is contained instead of escaping.
        StoredUpload upload = fileUploadService.storeFile(
                multipartFile("../../../../etc/passwd.java", "pwned")
        );

        assertThat(upload.getStoredFile().getParent()).isEqualTo(upload.getUploadDirectory());
        assertThat(upload.getStoredFile().getFileName().toString()).isEqualTo("passwd.java");
        assertThat(upload.getStoredFile().normalize()).startsWith(uploads);

        // Nothing was created next to, or above, the upload root.
        assertThat(uploads.getParent().resolve("etc")).doesNotExist();
    }

    @Test
    void storeContainsABackslashSeparatedTraversalToo() throws Exception {

        // A backslash is an ordinary filename character on Linux, so the container would happily
        // accept this as a plain name if only the platform separator were stripped.
        StoredUpload upload = fileUploadService.storeFile(
                multipartFile("..\\..\\Windows\\System32\\evil.java", "pwned")
        );

        assertThat(upload.getStoredFile().getParent()).isEqualTo(upload.getUploadDirectory());
        assertThat(upload.getStoredFile().getFileName().toString()).isEqualTo("evil.java");
    }

    @Test
    void storeRejectsAFileNameThatIsOnlyAParentReference() {

        assertThatThrownBy(() -> fileUploadService.storeFile(multipartFile("dir/..", "x")))
                .isInstanceOf(FileValidationException.class);

        assertThatThrownBy(() -> fileUploadService.storeFile(multipartFile("", "x")))
                .isInstanceOf(FileValidationException.class);
    }

    @Test
    void aFailedStoreLeavesNoDirectoryBehind() {

        // A filename that cannot be resolved fails after the upload directory already exists, so
        // the store has to remove its own partial work.
        assertThatThrownBy(() -> fileUploadService.storeFile(multipartFile("subdir/", "x")))
                .isInstanceOf(FileValidationException.class);

        assertThatCode(() -> {
            try (var entries = Files.list(uploads)) {
                assertThat(entries).isEmpty();
            }
        }).doesNotThrowAnyException();

        assertThat(uploads).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Recursive cleanup
    // ---------------------------------------------------------------------------------------

    @Test
    void deleteRemovesNestedDirectoriesAndFiles() throws Exception {

        Path upload = uploadDirectory("upload-nested");
        Files.createDirectories(upload.resolve("src/main/java/com/example"));
        Files.writeString(upload.resolve("src/main/java/com/example/App.java"), "class App {}");
        Files.writeString(upload.resolve("README.md"), "# demo");

        fileUploadService.deleteUpload(upload);

        assertThat(upload).doesNotExist();

        // The parent is the shared volume mount point - it must still be there for the next upload.
        assertThat(uploads).exists().isDirectory();
    }

    @Test
    void deleteRemovesAStoredUploadCompletely() throws Exception {

        StoredUpload upload = fileUploadService.storeFile(multipartFile("Main.java", "class Main {}"));

        fileUploadService.deleteUpload(upload.getUploadDirectory());

        assertThat(upload.getStoredFile()).doesNotExist();
        assertThat(upload.getUploadDirectory()).doesNotExist();
        assertThat(uploads).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------

    @Test
    void deleteIgnoresANullPath() {

        assertThatCode(() -> fileUploadService.deleteUpload(null)).doesNotThrowAnyException();
        assertThat(uploads).exists();
    }

    @Test
    void deleteIgnoresAPathThatNoLongerExists() {

        Path alreadyGone = uploads.resolve("upload-already-deleted");

        assertThatCode(() -> fileUploadService.deleteUpload(alreadyGone)).doesNotThrowAnyException();
        assertThat(uploads).exists();
    }

    @Test
    void deleteRefusesTheSharedUploadRootItself() throws Exception {

        Path survivor = uploadDirectory("upload-survivor");

        fileUploadService.deleteUpload(uploads);

        assertThat(uploads).exists();
        assertThat(survivor).exists();
    }

    @Test
    void deleteRefusesTheParentOfTheSharedUploadRoot() {

        // The /app case: one level above the configured directory.
        fileUploadService.deleteUpload(uploads.getParent());

        assertThat(uploads.getParent()).exists();
        assertThat(uploads).exists();
    }

    @Test
    void deleteRefusesAPathOutsideTheUploadRoot() throws Exception {

        Path outside = Files.createDirectory(unrelated.resolve("upload-elsewhere"));
        Files.writeString(outside.resolve("important.txt"), "keep me");

        fileUploadService.deleteUpload(outside);

        assertThat(outside).exists();
        assertThat(outside.resolve("important.txt")).exists();
    }

    @Test
    void deleteRefusesAPathThatEscapesTheUploadRootByTraversal() throws Exception {

        Path outside = Files.createDirectory(unrelated.resolve("upload-traversed"));

        // Starts inside the upload root but climbs back out through .., so it only looks like a
        // managed path until it is normalized.
        Path traversal = uploads.resolve(uploads.relativize(outside));
        assertThat(traversal.toString()).contains("..");

        fileUploadService.deleteUpload(traversal);

        assertThat(outside).exists();
    }

    @Test
    void deleteRefusesAnUnrelatedDirectoryInsideTheUploadRoot() throws Exception {

        Path notOurs = Files.createDirectory(uploads.resolve("some-other-data"));
        Files.writeString(notOurs.resolve("keep.txt"), "not an upload");

        fileUploadService.deleteUpload(notOurs);

        assertThat(notOurs).exists();
        assertThat(notOurs.resolve("keep.txt")).exists();
    }

    @Test
    void deleteRefusesADirectoryNestedInsideAnUpload() throws Exception {

        Path upload = uploadDirectory("upload-nested-target");
        Path insideUpload = Files.createDirectories(upload.resolve("src"));

        // Only the upload root directory is a legitimate cleanup target; anything deeper is not.
        fileUploadService.deleteUpload(insideUpload);

        assertThat(insideUpload).exists();
        assertThat(upload).exists();
    }

    @Test
    void deleteRefusesTheStoredFileItselfRatherThanItsDirectory() throws Exception {

        StoredUpload upload = fileUploadService.storeFile(multipartFile("Main.java", "class Main {}"));

        // The file is a child of the upload directory, not of the upload root, so it is refused.
        fileUploadService.deleteUpload(upload.getStoredFile());

        assertThat(upload.getStoredFile()).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Cleanup failure handling
    // ---------------------------------------------------------------------------------------

    @Test
    void aCleanupFailureIsReportedWithoutThrowing() throws Exception {

        Path upload = uploadDirectory("upload-locked");
        Path locked = upload.resolve("locked.bin");
        Files.writeString(locked, "held open");

        // An entry the OS will not let go of. On a platform that allows the delete anyway the
        // cleanup simply succeeds - either way it must not throw, and must not take the root.
        try (InputStream held = Files.newInputStream(locked)) {
            assertThat(held).isNotNull();
            assertThatCode(() -> fileUploadService.deleteUpload(upload)).doesNotThrowAnyException();
        }

        assertThat(uploads).exists();

        // Once the handle is released the next attempt finishes the job.
        fileUploadService.deleteUpload(upload);
        assertThat(upload).doesNotExist();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** A per-upload directory as storeFile would have created it, plus one file inside it. */
    private Path uploadDirectory(String name) throws IOException {

        Path upload = Files.createDirectory(uploads.resolve(name));
        Files.writeString(upload.resolve("file.txt"), "content");

        return upload;
    }

    private MockMultipartFile multipartFile(String originalFilename, String content) {

        return new MockMultipartFile("file", originalFilename, "text/plain", content.getBytes());
    }
}
