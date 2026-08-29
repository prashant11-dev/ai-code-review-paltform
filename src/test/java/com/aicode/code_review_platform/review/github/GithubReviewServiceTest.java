package com.aicode.code_review_platform.review.github;

import com.aicode.code_review_platform.auth.User;
import com.aicode.code_review_platform.enums.AppEnums;
import com.aicode.code_review_platform.review.CodeReview;
import com.aicode.code_review_platform.review.CodeReviewRepo;
import com.aicode.code_review_platform.review.ReviewChunk;
import com.aicode.code_review_platform.review.github.dto.CodeChunk;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import com.aicode.code_review_platform.review.github.dto.GithubReviewRequest;
import com.aicode.code_review_platform.review.github.exception.GithubReviewException;
import com.aicode.code_review_platform.review.github.exception.RepositoryCloneException;
import com.aicode.code_review_platform.review.github.service.ChunkGeneratorService;
import com.aicode.code_review_platform.review.github.service.CodeReaderService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryScannerService;
import com.aicode.code_review_platform.review.github.service.ReviewChunkService;
import com.aicode.code_review_platform.review.rabbitmq.ChunkProducerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STEP 6.2 - lifecycle tests for the orchestration layer: whatever the pipeline does, the clone is
 * handed back to {@link RepositoryCloneService} for deletion exactly once.
 *
 * <p>Cleanup itself is covered separately in {@code RepositoryCloneServiceImplTest}; here the
 * service is a mock, so what is verified is that the finally block runs on every path.
 */
@ExtendWith(MockitoExtension.class)
class GithubReviewServiceTest {

    private static final Long REVIEW_ID = 7L;

    private static final String REPOSITORY_URL = "https://github.com/example/demo.git";

    private static final Path CLONE_PATH = Paths.get("temp-repositories", "review-7-abc");

    @Mock
    private CodeReviewRepo codeReviewRepo;

    @Mock
    private RepositoryCloneService repositoryCloneService;

    @Mock
    private RepositoryScannerService repositoryScannerService;

    @Mock
    private CodeReaderService codeReaderService;

    @Mock
    private ChunkGeneratorService chunkGeneratorService;

    @Mock
    private ReviewChunkService reviewChunkService;

    @Mock
    private ChunkProducerService chunkProducerService;

    @InjectMocks
    private GithubReviewService githubReviewService;

    private GithubReviewRequest request;

    private User user;

    @BeforeEach
    void setUp() {

        request = new GithubReviewRequest();
        request.setRepositoryUrl(REPOSITORY_URL);

        user = new User();

        // The repository assigns the id on the first save, the way the database would.
        when(codeReviewRepo.save(any(CodeReview.class))).thenAnswer(invocation -> {
            CodeReview saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(REVIEW_ID);
            }
            return saved;
        });
    }

    @Test
    void successfulSubmissionCleansUpTheClone() throws Exception {

        when(repositoryCloneService.cloneRepository(REPOSITORY_URL, REVIEW_ID)).thenReturn(CLONE_PATH);
        when(repositoryScannerService.scanRepository(CLONE_PATH)).thenReturn(List.of(Paths.get("App.java")));
        when(codeReaderService.readFiles(anyList(), eq(CLONE_PATH))).thenReturn(List.of(codeFile()));
        when(chunkGeneratorService.generateChunks(anyList())).thenReturn(List.of(codeChunk()));
        when(reviewChunkService.createPendingChunks(any(CodeReview.class), anyList()))
                .thenReturn(List.of(new ReviewChunk()));

        var response = githubReviewService.submitGithubReview(request, user);

        assertThat(response.getStatus()).isEqualTo(AppEnums.ReviewStatus.PROCESSING);
        verify(repositoryCloneService).deleteRepository(CLONE_PATH);
    }

    @Test
    void scanFailureStillCleansUpTheClone() throws Exception {

        when(repositoryCloneService.cloneRepository(REPOSITORY_URL, REVIEW_ID)).thenReturn(CLONE_PATH);
        when(repositoryScannerService.scanRepository(CLONE_PATH)).thenThrow(new IOException("disk gone"));

        assertThatThrownBy(() -> githubReviewService.submitGithubReview(request, user))
                .isInstanceOf(GithubReviewException.class);

        verify(repositoryCloneService).deleteRepository(CLONE_PATH);
    }

    @Test
    void chunkGenerationFailureStillCleansUpTheClone() throws Exception {

        when(repositoryCloneService.cloneRepository(REPOSITORY_URL, REVIEW_ID)).thenReturn(CLONE_PATH);
        when(repositoryScannerService.scanRepository(CLONE_PATH)).thenReturn(List.of(Paths.get("App.java")));
        when(codeReaderService.readFiles(anyList(), eq(CLONE_PATH))).thenReturn(List.of(codeFile()));
        when(chunkGeneratorService.generateChunks(anyList())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> githubReviewService.submitGithubReview(request, user))
                .isInstanceOf(GithubReviewException.class);

        verify(repositoryCloneService).deleteRepository(CLONE_PATH);
    }

    @Test
    void cloneFailureLeavesNothingForTheOrchestratorToDelete() {

        when(repositoryCloneService.cloneRepository(REPOSITORY_URL, REVIEW_ID))
                .thenThrow(new RepositoryCloneException("no such repository", new IOException()));

        assertThatThrownBy(() -> githubReviewService.submitGithubReview(request, user))
                .isInstanceOf(GithubReviewException.class);

        // The clone removed its own partial directory, so the finally block gets a null path -
        // which deleteRepository has to tolerate.
        verify(repositoryCloneService).deleteRepository(null);
    }

    private CodeFile codeFile() {

        return CodeFile.builder()
                .fileName("App.java")
                .relativePath("App.java")
                .language("java")
                .content("class App {}")
                .build();
    }

    private CodeChunk codeChunk() {

        return CodeChunk.builder()
                .chunkNumber(1)
                .files(List.of(codeFile()))
                .totalCharacters(12)
                .build();
    }
}
