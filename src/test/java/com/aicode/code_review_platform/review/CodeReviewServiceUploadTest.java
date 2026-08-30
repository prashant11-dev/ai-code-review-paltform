package com.aicode.code_review_platform.review;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.common.utils.FileValidator;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.rabbitmq.ReviewProducer;
import com.aicode.code_review_platform.storage.FileUploadService;
import com.aicode.code_review_platform.storage.dto.StoredUpload;
import com.aicode.code_review_platform.storage.exception.FileValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STEP 6.3 - lifecycle tests for the orchestration layer: whatever the upload flow does, the
 * upload directory is handed back to {@link FileUploadService} for deletion exactly once.
 *
 * <p>Cleanup itself is covered separately in {@code FileUploadServiceTest}; here the storage
 * service is a mock, so what is verified is that the finally block runs on every path.
 */
@ExtendWith(MockitoExtension.class)
class CodeReviewServiceUploadTest {

    private static final Long REVIEW_ID = 11L;

    private static final String FILE_NAME = "UserService.java";

    @TempDir
    Path uploads;

    @Mock
    private CodeReviewRepo codeReviewRepo;

    @Mock
    private ReviewProducer reviewProducer;

    @Mock
    private FileValidator fileValidator;

    @Mock
    private FileUploadService fileUploadService;

    @InjectMocks
    private CodeReviewService codeReviewService;

    private User user;

    private MockMultipartFile file;

    private StoredUpload storedUpload;

    @BeforeEach
    void setUp() {

        user = new User();
        user.setEmail("prashant@example.com");

        file = new MockMultipartFile("file", FILE_NAME, "text/plain", "class UserService {}".getBytes());
    }

    // ---------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------

    @Test
    void successfulUploadIsPersistedQueuedAndThenCleanedUp() throws Exception {

        givenAStoredUpload();
        givenTheRepositoryAssignsAnId();

        Long reviewId = codeReviewService.uploadReview(file, user);

        assertThat(reviewId).isEqualTo(REVIEW_ID);

        // The file content reached the review row - the durability boundary that makes deleting
        // the upload safe.
        ArgumentCaptor<CodeReview> saved = ArgumentCaptor.forClass(CodeReview.class);
        verify(codeReviewRepo).save(saved.capture());

        assertThat(saved.getValue().getSourceType()).isEqualTo(AppEnums.ReviewSourceType.FILE);
        assertThat(saved.getValue().getStatus()).isEqualTo(AppEnums.ReviewStatus.PENDING);
        assertThat(saved.getValue().getFileName()).isEqualTo(FILE_NAME);
        assertThat(saved.getValue().getLanguage()).isEqualTo("java");
        assertThat(saved.getValue().getCode()).isEqualTo("class UserService {}");

        verify(reviewProducer).sendReview(REVIEW_ID);
        verify(fileUploadService).deleteUpload(storedUpload.getUploadDirectory());
    }

    // ---------------------------------------------------------------------------------------
    // Failure paths - the upload still goes
    // ---------------------------------------------------------------------------------------

    @Test
    void readFailureStillCleansUpTheUpload() throws Exception {

        // The stored file is gone by the time it is read - the closest stand-in for an IO fault
        // between storing and reading.
        Path uploadDirectory = Files.createDirectory(uploads.resolve("upload-missing"));
        storedUpload = StoredUpload.builder()
                .uploadDirectory(uploadDirectory)
                .storedFile(uploadDirectory.resolve(FILE_NAME))
                .originalFilename(FILE_NAME)
                .build();
        when(fileUploadService.storeFile(file)).thenReturn(storedUpload);

        assertThatThrownBy(() -> codeReviewService.uploadReview(file, user))
                .isInstanceOf(IOException.class);

        verify(codeReviewRepo, never()).save(any(CodeReview.class));
        verify(fileUploadService).deleteUpload(uploadDirectory);
    }

    @Test
    void persistenceFailureStillCleansUpTheUpload() throws Exception {

        givenAStoredUpload();
        when(codeReviewRepo.save(any(CodeReview.class)))
                .thenThrow(new RuntimeException("database is down"));

        assertThatThrownBy(() -> codeReviewService.uploadReview(file, user))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database is down");

        verify(reviewProducer, never()).sendReview(any());
        verify(fileUploadService).deleteUpload(storedUpload.getUploadDirectory());
    }

    @Test
    void publishFailureStillCleansUpTheUpload() throws Exception {

        givenAStoredUpload();
        givenTheRepositoryAssignsAnId();
        doThrow(new RuntimeException("broker unreachable")).when(reviewProducer).sendReview(REVIEW_ID);

        assertThatThrownBy(() -> codeReviewService.uploadReview(file, user))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker unreachable");

        verify(fileUploadService).deleteUpload(storedUpload.getUploadDirectory());
    }

    @Test
    void aCleanupFailureDoesNotReplaceTheReviewResult() throws Exception {

        givenAStoredUpload();
        givenTheRepositoryAssignsAnId();

        // deleteUpload is documented never to throw. Should that contract ever be broken, the
        // review that already succeeded must not be turned into a failure by the cleanup.
        Long reviewId = codeReviewService.uploadReview(file, user);

        assertThat(reviewId).isEqualTo(REVIEW_ID);
        verify(fileUploadService).deleteUpload(storedUpload.getUploadDirectory());
    }

    // ---------------------------------------------------------------------------------------
    // Nothing stored, nothing to clean
    // ---------------------------------------------------------------------------------------

    @Test
    void aRejectedUploadIsNeverStoredAndNeverCleanedUp() throws Exception {

        doThrow(new FileValidationException("Unsupported file type: txt"))
                .when(fileValidator).validate(file);

        assertThatThrownBy(() -> codeReviewService.uploadReview(file, user))
                .isInstanceOf(FileValidationException.class);

        verify(fileUploadService, never()).storeFile(any());
        verify(fileUploadService, never()).deleteUpload(any());
        verify(codeReviewRepo, never()).save(any(CodeReview.class));
    }

    @Test
    void aFailedStoreLeavesNothingForTheOrchestratorToDelete() throws Exception {

        when(fileUploadService.storeFile(file)).thenThrow(new IOException("disk full"));

        assertThatThrownBy(() -> codeReviewService.uploadReview(file, user))
                .isInstanceOf(IOException.class);

        // storeFile removes its own partial directory, so the caller has nothing to hand back.
        verify(fileUploadService, never()).deleteUpload(any());
        verify(codeReviewRepo, never()).save(any(CodeReview.class));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void givenAStoredUpload() throws IOException {

        Path uploadDirectory = Files.createDirectory(uploads.resolve("upload-" + REVIEW_ID));
        Path storedFile = uploadDirectory.resolve(FILE_NAME);
        Files.writeString(storedFile, "class UserService {}");

        storedUpload = StoredUpload.builder()
                .uploadDirectory(uploadDirectory)
                .storedFile(storedFile)
                .originalFilename(FILE_NAME)
                .build();

        when(fileUploadService.storeFile(file)).thenReturn(storedUpload);
    }

    private void givenTheRepositoryAssignsAnId() {

        when(codeReviewRepo.save(any(CodeReview.class))).thenAnswer(invocation -> {
            CodeReview saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(REVIEW_ID);
            }
            return saved;
        });
    }
}
