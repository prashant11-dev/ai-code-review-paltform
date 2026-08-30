package com.aicode.code_review_platform.cleanup;

import com.aicode.code_review_platform.cleanup.dto.CleanupSummary;
import com.aicode.code_review_platform.review.github.RepositoryConfig;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneServiceImpl;
import com.aicode.code_review_platform.storage.FileStorageConfig;
import com.aicode.code_review_platform.storage.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * STEP 6.4 - tests for the startup and scheduled safety net.
 *
 * <p>Almost nothing is mocked: the tests run against real temp directory trees wired into the
 * real RepositoryCloneServiceImpl and FileUploadService, because what is being verified is which
 * files survive a cleanup run and which do not. Age is simulated by back-dating modification
 * times rather than by waiting.
 *
 * <p>Mockito appears only where a failure has to be forced - a deleter that does nothing, a
 * deleter that throws, a configuration that blows up.
 */
class TemporaryStorageCleanupServiceTest {

    private static final long MAX_AGE_MS = Duration.ofHours(2).toMillis();

    /** Stands in for /app/temp-repositories - the shared root that must always survive. */
    @TempDir
    Path tempRepositories;

    /** Stands in for /app/uploads - the shared root that must always survive. */
    @TempDir
    Path uploads;

    /** Sibling of both roots, used to prove cleanup will not reach outside of them. */
    @TempDir
    Path unrelated;

    private CleanupConfig cleanupConfig;

    private RepositoryCloneServiceImpl repositoryCloneService;

    private FileUploadService fileUploadService;

    private TemporaryStorageCleanupService cleanupService;

    @BeforeEach
    void setUp() {

        cleanupConfig = new CleanupConfig();
        cleanupConfig.setEnabled(true);
        cleanupConfig.setIntervalMs(Duration.ofHours(1).toMillis());
        cleanupConfig.setMaxAgeMs(MAX_AGE_MS);

        RepositoryConfig repositoryConfig = new RepositoryConfig();
        repositoryConfig.setTempDirectory(tempRepositories.toString());

        FileStorageConfig fileStorageConfig = new FileStorageConfig();
        fileStorageConfig.setUploadDir(uploads.toString());
        fileStorageConfig.setMaxFileSize(DataSize.ofMegabytes(10));

        repositoryCloneService = new RepositoryCloneServiceImpl();
        ReflectionTestUtils.setField(repositoryCloneService, "repositoryConfig", repositoryConfig);

        fileUploadService = new FileUploadService();
        ReflectionTestUtils.setField(fileUploadService, "fileStorageConfig", fileStorageConfig);

        cleanupService = new TemporaryStorageCleanupService(
                cleanupConfig,
                repositoryConfig,
                fileStorageConfig,
                repositoryCloneService,
                fileUploadService
        );
    }

    // ---------------------------------------------------------------------------------------
    // Enabled flag
    // ---------------------------------------------------------------------------------------

    @Test
    void disabledCleanupDeletesNothingHoweverStaleTheDataIs() throws Exception {

        Path repository = staleRepository("review-1-abandoned");
        Path upload = staleUpload("upload-abandoned");

        cleanupConfig.setEnabled(false);

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        // Not even scanned - the flag is checked before anything touches the filesystem.
        assertThat(summary).isEqualTo(CleanupSummary.empty());
        assertThat(repository).exists();
        assertThat(upload).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Age
    // ---------------------------------------------------------------------------------------

    @Test
    void freshDataIsPreserved() throws Exception {

        // Just created, so nowhere near the two hour threshold - this is the in-flight review.
        Path repository = repositoryDirectory("review-1-running");
        Path upload = uploadDirectory("upload-running");

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.deleted()).isZero();
        assertThat(summary.retained()).isEqualTo(2);
        assertThat(summary.failed()).isZero();
        assertThat(repository).exists();
        assertThat(upload).exists();
    }

    @Test
    void dataJustUnderTheMaxAgeIsPreserved() throws Exception {

        // 11:59 in the specification's example: older than most, still not eligible.
        Path repository = repositoryDirectory("review-1-almost-stale");
        age(repository, Duration.ofMillis(MAX_AGE_MS).minusMinutes(1));

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.deleted()).isZero();
        assertThat(repository).exists();
    }

    @Test
    void staleDataIsDeletedFromBothStorageAreas() throws Exception {

        Path repository = staleRepository("review-1-crashed");
        Path upload = staleUpload("upload-crashed");

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.deleted()).isEqualTo(2);
        assertThat(summary.failed()).isZero();
        assertThat(repository).doesNotExist();
        assertThat(upload).doesNotExist();
    }

    @Test
    void recentActivityInsideADirectoryKeepsItAlive() throws Exception {

        // The directory itself was created long ago - a clone that has been running for hours -
        // but its contents are still being written. It must not be treated as abandoned.
        Path repository = staleRepository("review-1-slow-clone");
        Files.setLastModifiedTime(repository.resolve(".git"), FileTime.from(Instant.now()));

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.deleted()).isZero();
        assertThat(summary.retained()).isEqualTo(1);
        assertThat(repository).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Recursion and volume
    // ---------------------------------------------------------------------------------------

    @Test
    void nestedStaleDirectoriesAreDeletedRecursively() throws Exception {

        Path repository = repositoryDirectory("review-1-deep");
        Path deep = Files.createDirectories(repository.resolve("src/main/java/com/example"));
        Files.writeString(deep.resolve("Application.java"), "class Application {}");
        Files.writeString(repository.resolve("src/main/java/com/example/Other.java"), "class Other {}");
        age(repository, Duration.ofHours(3));

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.deleted()).isEqualTo(1);
        assertThat(repository).doesNotExist();
        assertThat(deep).doesNotExist();
    }

    @Test
    void everyStaleEntryIsProcessedAndFreshOnesAreLeftAlone() throws Exception {

        Path firstStale = staleRepository("review-1-old");
        Path secondStale = staleRepository("review-2-old");
        Path thirdStale = staleUpload("upload-old");
        Path fresh = repositoryDirectory("review-3-current");

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.scanned()).isEqualTo(4);
        assertThat(summary.deleted()).isEqualTo(3);
        assertThat(summary.retained()).isEqualTo(1);
        assertThat(firstStale).doesNotExist();
        assertThat(secondStale).doesNotExist();
        assertThat(thirdStale).doesNotExist();
        assertThat(fresh).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Path safety
    // ---------------------------------------------------------------------------------------

    @Test
    void theStorageRootsThemselvesAreNeverDeleted() throws Exception {

        // Both roots are as old as the data in them, and end up completely empty. They still have
        // to be there afterwards - they are the docker volume mount points.
        staleRepository("review-1-old");
        staleUpload("upload-old");
        age(tempRepositories, Duration.ofDays(30));
        age(uploads, Duration.ofDays(30));

        cleanupService.cleanupStaleData("Scheduled");

        assertThat(tempRepositories).exists().isDirectory();
        assertThat(uploads).exists().isDirectory();
        assertThat(tempRepositories).isEmptyDirectory();
        assertThat(uploads).isEmptyDirectory();
    }

    @Test
    void staleEntriesWithoutTheManagedPrefixAreRefused() throws Exception {

        // Old enough, sitting in the right place, but not something this application created.
        Path notOurs = Files.createDirectory(tempRepositories.resolve("some-other-data"));
        Files.writeString(notOurs.resolve("keep.txt"), "not a clone");
        age(notOurs, Duration.ofDays(30));

        Path looseFile = uploads.resolve("1782587243209_Legacy.java");
        Files.writeString(looseFile, "class Legacy {}");
        Files.setLastModifiedTime(looseFile, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary.deleted()).isZero();
        assertThat(summary.retained()).isEqualTo(2);
        assertThat(notOurs).exists();
        assertThat(notOurs.resolve("keep.txt")).exists();
        assertThat(looseFile).exists();
    }

    @Test
    void staleDataOutsideTheConfiguredRootsIsNeverTouched() throws Exception {

        // A correctly named, ancient directory - but in a completely different tree. Cleanup only
        // ever lists the configured roots, so it is never even a candidate.
        Path outside = Files.createDirectory(unrelated.resolve("review-1-elsewhere"));
        Files.writeString(outside.resolve("file.txt"), "content");
        age(outside, Duration.ofDays(30));

        Path sibling = Files.createDirectory(unrelated.resolve("important-data"));
        Files.writeString(sibling.resolve("keep.txt"), "content");
        age(sibling, Duration.ofDays(30));

        cleanupService.cleanupStaleData("Scheduled");

        assertThat(outside).exists();
        assertThat(sibling.resolve("keep.txt")).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void aTraversingConfiguredRootStillOnlyDeletesItsOwnChildren() throws Exception {

        // The root configured with a ".." in it normalizes back onto the real temp directory, so
        // the parent check still holds and nothing above it becomes reachable.
        RepositoryConfig traversing = new RepositoryConfig();
        traversing.setTempDirectory(tempRepositories.resolve("nested").resolve("..").toString());

        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(uploads.toString());
        storageConfig.setMaxFileSize(DataSize.ofMegabytes(10));

        TemporaryStorageCleanupService service = new TemporaryStorageCleanupService(
                cleanupConfig, traversing, storageConfig, repositoryCloneService, fileUploadService
        );

        Path stale = staleRepository("review-1-old");
        Path parentOfRoot = tempRepositories.getParent();

        service.cleanupStaleData("Scheduled");

        assertThat(stale).doesNotExist();
        assertThat(tempRepositories).exists();
        assertThat(parentOfRoot).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Missing directories and failures
    // ---------------------------------------------------------------------------------------

    @Test
    void aMissingStorageRootIsHandledSafely() throws Exception {

        RepositoryConfig missing = new RepositoryConfig();
        missing.setTempDirectory(unrelated.resolve("never-created").toString());

        FileStorageConfig storageConfig = new FileStorageConfig();
        storageConfig.setUploadDir(uploads.toString());
        storageConfig.setMaxFileSize(DataSize.ofMegabytes(10));

        TemporaryStorageCleanupService service = new TemporaryStorageCleanupService(
                cleanupConfig, missing, storageConfig, repositoryCloneService, fileUploadService
        );

        Path upload = staleUpload("upload-old");

        CleanupSummary summary = assertNoThrow(() -> service.cleanupStaleData("Scheduled"));

        // The missing root contributes nothing and is not created; the other area still runs.
        assertThat(unrelated.resolve("never-created")).doesNotExist();
        assertThat(summary.failed()).isZero();
        assertThat(summary.deleted()).isEqualTo(1);
        assertThat(upload).doesNotExist();
    }

    @Test
    void aDeleterThatFailsIsCountedAndTheOtherAreaStillRuns() throws Exception {

        // Stands in for a file the process cannot remove - the deleter logs and returns, leaving
        // the directory behind.
        RepositoryCloneService stubborn = mock(RepositoryCloneService.class);

        TemporaryStorageCleanupService service = new TemporaryStorageCleanupService(
                cleanupConfig,
                repositoryConfigFor(tempRepositories),
                fileStorageConfigFor(uploads),
                stubborn,
                fileUploadService
        );

        Path repository = staleRepository("review-1-locked");
        Path upload = staleUpload("upload-old");

        CleanupSummary summary = assertNoThrow(() -> service.cleanupStaleData("Scheduled"));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.deleted()).isEqualTo(1);
        assertThat(repository).exists();
        assertThat(upload).doesNotExist();
    }

    @Test
    void aDeleterThatThrowsDoesNotAbortTheRun() throws Exception {

        RepositoryCloneService exploding = mock(RepositoryCloneService.class);
        doThrow(new RuntimeException("disk on fire"))
                .when(exploding).deleteRepository(any());

        TemporaryStorageCleanupService service = new TemporaryStorageCleanupService(
                cleanupConfig,
                repositoryConfigFor(tempRepositories),
                fileStorageConfigFor(uploads),
                exploding,
                fileUploadService
        );

        Path repository = staleRepository("review-1-explodes");
        Path upload = staleUpload("upload-old");

        CleanupSummary summary = assertNoThrow(() -> service.cleanupStaleData("Scheduled"));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.deleted()).isEqualTo(1);
        assertThat(repository).exists();
        assertThat(upload).doesNotExist();
    }

    // ---------------------------------------------------------------------------------------
    // Triggers
    // ---------------------------------------------------------------------------------------

    @Test
    void startupCleanupRemovesOnlyStaleData() throws Exception {

        Path leftBehind = staleRepository("review-1-from-previous-instance");
        Path staleUpload = staleUpload("upload-from-previous-instance");
        Path fresh = repositoryDirectory("review-2-current");

        cleanupService.cleanupOnStartup();

        assertThat(leftBehind).doesNotExist();
        assertThat(staleUpload).doesNotExist();
        assertThat(fresh).exists();
        assertThat(tempRepositories).exists();
        assertThat(uploads).exists();
    }

    @Test
    void startupCleanupNeverPropagatesAFailure() {

        // Whatever goes wrong, the application has to finish starting.
        CleanupConfig broken = mock(CleanupConfig.class);
        when(broken.isEnabled()).thenThrow(new IllegalStateException("configuration unavailable"));

        TemporaryStorageCleanupService service = new TemporaryStorageCleanupService(
                broken,
                repositoryConfigFor(tempRepositories),
                fileStorageConfigFor(uploads),
                repositoryCloneService,
                fileUploadService
        );

        assertThatCode(service::cleanupOnStartup).doesNotThrowAnyException();
    }

    @Test
    void scheduledCleanupRunsTheSameCleanup() throws Exception {

        Path stale = staleRepository("review-1-old");
        Path fresh = uploadDirectory("upload-current");

        cleanupService.cleanupOnSchedule();

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void scheduledCleanupIsWiredToTheConfiguredInterval() throws Exception {

        Method scheduled = TemporaryStorageCleanupService.class.getMethod("cleanupOnSchedule");
        Scheduled annotation = scheduled.getAnnotation(Scheduled.class);

        // fixedDelay, so a slow run delays the next one instead of overlapping with it, and both
        // values come from the property rather than from code.
        assertThat(annotation).isNotNull();
        assertThat(annotation.fixedDelayString()).isEqualTo("${app.cleanup.interval-ms}");
        assertThat(annotation.initialDelayString()).isEqualTo("${app.cleanup.interval-ms}");
    }

    @Test
    void aSecondConcurrentRunIsSkippedRatherThanOverlapping() throws Exception {

        Path stale = staleRepository("review-1-old");

        // A run that is already in progress - the state the guard exists for.
        ReflectionTestUtils.setField(cleanupService, "running", new AtomicBoolean(true));

        CleanupSummary summary = cleanupService.cleanupStaleData("Scheduled");

        assertThat(summary).isEqualTo(CleanupSummary.empty());
        assertThat(stale).exists();
    }

    // ---------------------------------------------------------------------------------------
    // The normal, immediate cleanup is unchanged
    // ---------------------------------------------------------------------------------------

    @Test
    void theImmediateReviewCleanupStillDeletesFreshDirectories() throws Exception {

        // The finally-block path is not age-gated and must stay that way: a review that has just
        // finished deletes its own data straight away, without waiting for max-age.
        Path repository = repositoryDirectory("review-1-just-finished");
        Path upload = uploadDirectory("upload-just-finished");

        repositoryCloneService.deleteRepository(repository);
        fileUploadService.deleteUpload(upload);

        assertThat(repository).doesNotExist();
        assertThat(upload).doesNotExist();
        assertThat(tempRepositories).exists();
        assertThat(uploads).exists();
    }

    @Test
    void theImmediateReviewCleanupStillRefusesTheStorageRoots() {

        repositoryCloneService.deleteRepository(tempRepositories);
        fileUploadService.deleteUpload(uploads);

        assertThat(tempRepositories).exists();
        assertThat(uploads).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** A per-review directory as the clone would have left it, with a .git child. */
    private Path repositoryDirectory(String name) throws IOException {

        Path repository = Files.createDirectory(tempRepositories.resolve(name));
        Files.createDirectory(repository.resolve(".git"));
        Files.writeString(repository.resolve("README.md"), "# demo");

        return repository;
    }

    /** A per-upload directory as storeFile would have left it, with the stored file inside. */
    private Path uploadDirectory(String name) throws IOException {

        Path upload = Files.createDirectory(uploads.resolve(name));
        Files.writeString(upload.resolve("UserService.java"), "class UserService {}");

        return upload;
    }

    private Path staleRepository(String name) throws IOException {

        Path repository = repositoryDirectory(name);
        age(repository, Duration.ofHours(3));

        return repository;
    }

    private Path staleUpload(String name) throws IOException {

        Path upload = uploadDirectory(name);
        age(upload, Duration.ofHours(3));

        return upload;
    }

    /**
     * Back-dates a directory and its direct children, which is exactly the set of timestamps
     * staleness is decided on. The directory itself goes last, because touching a child updates
     * its parent.
     */
    private void age(Path directory, Duration by) throws IOException {

        FileTime when = FileTime.from(Instant.now().minus(by));

        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                Files.setLastModifiedTime(child, when);
            }
        }

        Files.setLastModifiedTime(directory, when);
    }

    private RepositoryConfig repositoryConfigFor(Path directory) {

        RepositoryConfig config = new RepositoryConfig();
        config.setTempDirectory(directory.toString());

        return config;
    }

    private FileStorageConfig fileStorageConfigFor(Path directory) {

        FileStorageConfig config = new FileStorageConfig();
        config.setUploadDir(directory.toString());
        config.setMaxFileSize(DataSize.ofMegabytes(10));

        return config;
    }

    /**
     * Runs a cleanup once and fails the test if it threw - cleanup is never allowed to propagate.
     * Run once, not twice: a second pass would see the filesystem the first one already changed.
     */
    private CleanupSummary assertNoThrow(Supplier<CleanupSummary> run) {

        AtomicReference<CleanupSummary> result = new AtomicReference<>();

        assertThatCode(() -> result.set(run.get())).doesNotThrowAnyException();

        return result.get();
    }
}
