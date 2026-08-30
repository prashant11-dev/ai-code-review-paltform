package com.aicode.code_review_platform.cleanup;

import com.aicode.code_review_platform.cleanup.dto.CleanupSummary;
import com.aicode.code_review_platform.review.github.RepositoryConfig;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneService;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneServiceImpl;
import com.aicode.code_review_platform.storage.FileStorageConfig;
import com.aicode.code_review_platform.storage.FileUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * STEP 6.4 - the safety net for temporary review data.
 *
 * <p>The primary cleanup is unchanged and stays where it was: every review deletes its own
 * checkout or upload from a finally block, immediately, whether it succeeded or failed. That
 * covers everything except the cases where the finally block never runs - a crash, a container
 * restart, a kill during processing. What is left behind then would sit on the volume forever,
 * and this service is what removes it.
 *
 * <p>It runs twice: once when the application is ready (clearing what a previous instance left
 * behind) and then on a fixed schedule. Both paths do exactly the same thing, and both are
 * strictly additive - nothing here replaces or weakens the immediate cleanup.
 *
 * <p>The deletion itself is delegated back to the services that own each storage area, so the
 * path rules written for STEP 6.2 and 6.3 apply unchanged and are enforced twice: once here,
 * before an entry is even considered, and again inside the owning service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemporaryStorageCleanupService {

    /** Log prefixes. The wording differs per trigger so the two runs are greppable apart. */
    private static final String STARTUP_TRIGGER = "Temporary storage";

    private static final String SCHEDULED_TRIGGER = "Scheduled";

    private final CleanupConfig cleanupConfig;

    private final RepositoryConfig repositoryConfig;

    private final FileStorageConfig fileStorageConfig;

    private final RepositoryCloneService repositoryCloneService;

    private final FileUploadService fileUploadService;

    /**
     * Guards against two runs overlapping. fixedDelay on a single-threaded scheduler already
     * prevents one scheduled run from starting before the previous finished, so this exists for
     * the case that is not covered: a slow startup cleanup still running when the first scheduled
     * run comes due.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * STEP 6.4 - startup cleanup. Runs once the context is up, and removes whatever a previous
     * instance left behind when it died mid-review.
     *
     * <p>Age still decides: this is not a "wipe the volumes on boot" hook. Data younger than
     * app.cleanup.max-age-ms is kept, which also means a second instance starting alongside a
     * running one cannot delete the running instance's work.
     *
     * <p>Nothing thrown from here is allowed to escape. A listener that throws on
     * ApplicationReadyEvent takes SpringApplication.run down with it, and a full disk or a
     * permission problem on a temp directory is not a reason to refuse to serve requests.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {

        try {
            cleanupStaleData(STARTUP_TRIGGER);
        } catch (Exception e) {
            log.error(
                    "Temporary storage cleanup failed during startup, continuing anyway: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    /**
     * STEP 6.4 - scheduled cleanup. fixedDelay, not fixedRate: the next run is measured from the
     * end of the previous one, so a long run can never queue up behind itself. The initial delay
     * keeps it from firing on top of the startup cleanup.
     */
    @Scheduled(
            fixedDelayString = "${app.cleanup.interval-ms}",
            initialDelayString = "${app.cleanup.interval-ms}"
    )
    public void cleanupOnSchedule() {

        try {
            cleanupStaleData(SCHEDULED_TRIGGER);
        } catch (Exception e) {
            // A scheduled method that throws only loses that one execution, but the log would be a
            // bare stack trace from the scheduler. Report it in context instead.
            log.error("Scheduled cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * STEP 6.4 - the actual work, shared by both triggers.
     *
     * <p>Check enabled, scan each storage root one level deep, keep only entries that are ours
     * and old enough, delete those, log what happened. Never throws: both callers are background
     * work with nobody to report an exception to.
     *
     * @param trigger how the run started, used as the log prefix
     * @return what the run did, for the completion log and for tests
     */
    public CleanupSummary cleanupStaleData(String trigger) {

        if (!cleanupConfig.isEnabled()) {
            log.debug("{} cleanup skipped: app.cleanup.enabled is false", trigger);
            return CleanupSummary.empty();
        }

        // Refused rather than queued: the run that is already in progress is doing this exact
        // work, and a second pass over the same directories would only race with it.
        if (!running.compareAndSet(false, true)) {
            log.warn("{} cleanup skipped: a previous cleanup run is still in progress", trigger);
            return CleanupSummary.empty();
        }

        Instant startedAt = Instant.now();

        log.info(
                "{} cleanup started (maxAgeMs={}, intervalMs={})",
                trigger,
                cleanupConfig.getMaxAgeMs(),
                cleanupConfig.getIntervalMs()
        );

        // Anything last touched before this instant is stale. Computed once, so every entry in a
        // run is judged against the same clock reading.
        Instant threshold = startedAt.minusMillis(cleanupConfig.getMaxAgeMs());

        // Declared outside the try so a failure part-way still reports what was done before it.
        CleanupSummary summary = CleanupSummary.empty();

        try {
            for (TemporaryStorageArea area : storageAreas()) {
                summary = summary.plus(cleanupArea(area, threshold));
            }
        } catch (RuntimeException e) {
            // cleanupArea already absorbs its own failures, so reaching this is unexpected - which
            // is exactly why it is caught rather than left to the scheduler.
            log.error("{} cleanup aborted early: {}", trigger, e.getMessage(), e);
        } finally {
            running.set(false);
        }

        log.info(
                "{} cleanup completed in {} ms: scanned={}, deleted={}, retained={}, failed={}",
                trigger,
                Duration.between(startedAt, Instant.now()).toMillis(),
                summary.scanned(),
                summary.deleted(),
                summary.retained(),
                summary.failed()
        );

        return summary;
    }

    /**
     * The two roots this service is allowed to touch, resolved from the same configuration the
     * review flow writes into - /app/temp-repositories and /app/uploads in the container, each on
     * its own docker volume. Built per run rather than cached, so a configuration change (or a
     * test pointing them somewhere else) is picked up.
     *
     * <p>Deletion is delegated to the owning service. Those methods are recursive, refuse anything
     * that is not one of their own per-review directories, and never throw.
     */
    private List<TemporaryStorageArea> storageAreas() {

        return List.of(
                new TemporaryStorageArea(
                        "temp-repositories",
                        absolute(repositoryConfig.getTempDirectory()),
                        RepositoryCloneServiceImpl.REPOSITORY_DIRECTORY_PREFIX,
                        repositoryCloneService::deleteRepository
                ),
                new TemporaryStorageArea(
                        "uploads",
                        absolute(fileStorageConfig.getUploadDir()),
                        FileUploadService.UPLOAD_DIRECTORY_PREFIX,
                        fileUploadService::deleteUpload
                )
        );
    }

    /**
     * Scans one storage root, one level deep, and deletes the stale entries in it.
     *
     * <p>Only direct children are listed - the root is never walked recursively at scan time, and
     * the root itself is never a candidate. Fault tolerance is per entry: an entry that cannot be
     * read or cannot be deleted is counted and logged, and the loop moves on to the next one.
     */
    private CleanupSummary cleanupArea(TemporaryStorageArea area, Instant threshold) {

        Path root = area.root();

        // Nothing to do before the first review has run, or if the volume is not mounted. Not an
        // error, and deliberately not created here - the review services own that.
        if (!Files.isDirectory(root)) {
            log.debug(
                    "Cleanup skipped for storageType={}: {} does not exist",
                    area.storageType(),
                    root
            );
            return CleanupSummary.empty();
        }

        int scanned = 0;
        int deleted = 0;
        int retained = 0;
        int failed = 0;

        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {

            for (Path child : children) {

                scanned++;

                Path candidate = child.toAbsolutePath().normalize();

                // Path safety gate. Everything past it is deleted recursively.
                if (!isEligible(root, area.prefix(), candidate)) {
                    // Debug, not warn: an unrelated file sitting in the root is somebody else's
                    // business, and warning about it every hour would be noise.
                    log.debug(
                            "Cleanup retained storageType={}, entry={}: not a managed {} entry",
                            area.storageType(),
                            candidate.getFileName(),
                            area.prefix()
                    );
                    retained++;
                    continue;
                }

                Instant lastActivity;

                try {
                    lastActivity = lastActivityTime(candidate);
                } catch (IOException | RuntimeException e) {
                    log.error(
                            "Cleanup could not read timestamps for storageType={}, path={}: {}",
                            area.storageType(),
                            candidate,
                            e.getMessage(),
                            e
                    );
                    failed++;
                    continue;
                }

                // Not stale yet. This is the whole protection for an in-flight review: its
                // directory was touched recently, so it is left alone.
                if (!lastActivity.isBefore(threshold)) {
                    log.debug(
                            "Cleanup retained storageType={}, entry={}: last activity {} is newer than the threshold",
                            area.storageType(),
                            candidate.getFileName(),
                            lastActivity
                    );
                    retained++;
                    continue;
                }

                if (deleteStaleEntry(area, candidate, lastActivity)) {
                    deleted++;
                } else {
                    failed++;
                }
            }

        } catch (IOException | RuntimeException e) {
            // The listing itself failed, so the counts above are whatever was reached before it.
            // The other storage area still gets its turn.
            log.error(
                    "Cleanup failed to scan storageType={}, path={}: {}",
                    area.storageType(),
                    root,
                    e.getMessage(),
                    e
            );
            failed++;
        }

        return new CleanupSummary(scanned, deleted, retained, failed);
    }

    /**
     * Hands one stale entry to the service that owns it and reports whether it is actually gone.
     *
     * <p>The owning services log their own per-file detail and never throw, so the try/catch here
     * is for the unexpected - and it must not stop the entries after this one from being cleaned.
     * The name of the entry (review-&lt;id&gt;-&lt;uuid&gt; or upload-&lt;uuid&gt;) is what ties a
     * line in this log back to a specific review.
     */
    private boolean deleteStaleEntry(TemporaryStorageArea area, Path candidate, Instant lastActivity) {

        log.info(
                "Cleanup deleting stale storageType={}, entry={}, path={}, lastActivity={}",
                area.storageType(),
                candidate.getFileName(),
                candidate,
                lastActivity
        );

        try {
            area.deleter().accept(candidate);
        } catch (RuntimeException e) {
            log.error(
                    "Cleanup failed for storageType={}, path={}: {}",
                    area.storageType(),
                    candidate,
                    e.getMessage(),
                    e
            );
            return false;
        }

        // The deleters are best-effort and report their own failures by logging, so the only
        // reliable answer to "did it work" is to look.
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            log.error(
                    "Cleanup incomplete for storageType={}, path={}: entry still exists after deletion",
                    area.storageType(),
                    candidate
            );
            return false;
        }

        log.info("Cleanup deleted storageType={}, path={}", area.storageType(), candidate);
        return true;
    }

    /**
     * STEP 6.4 - the path safety rule, identical to the one the review flow already enforces: an
     * entry is eligible only when it is a direct child of the configured root and carries the
     * prefix the owning service creates.
     *
     * <p>That rules out the root itself (/app/uploads, /app/temp-repositories), anything above it
     * (/app, /), anything outside it, anything nested deeper inside a review directory, and any
     * unrelated file or directory that happens to sit next to ours. Because only direct children
     * of the root are ever listed, a traversal would have to survive normalization and still come
     * back out as a child of the root - which is exactly what the parent check asserts.
     */
    private boolean isEligible(Path root, String prefix, Path candidate) {

        // A symlink planted in the root could otherwise point a recursive delete at an unrelated
        // tree. Refused outright rather than followed.
        if (Files.isSymbolicLink(candidate)) {
            log.warn("Cleanup refused {}: symbolic links are never followed", candidate);
            return false;
        }

        // Belt and braces - the root is not one of its own children, so this cannot be true, but
        // it is the one mistake that would be unrecoverable.
        if (candidate.equals(root)) {
            log.error("Cleanup refused {}: the storage root itself is never deleted", candidate);
            return false;
        }

        return root.equals(candidate.getParent())
                && candidate.getFileName().toString().startsWith(prefix);
    }

    /**
     * STEP 6.4 - when this entry was last touched, used to decide staleness.
     *
     * <p>The entry's own modification time is not enough on its own: a clone creates its directory
     * up front and then writes into subdirectories, which does not bubble up to the parent. So the
     * newest of the directory and its direct children is taken - one extra listing, no recursion,
     * and enough to keep a long-running review looking alive.
     *
     * <p>Timestamps are read without following links, and a child that disappears mid-scan (the
     * review finishing normally, most likely) is skipped rather than failing the entry.
     */
    private Instant lastActivityTime(Path entry) throws IOException {

        Instant newest = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant();

        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            return newest;
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {

            for (Path child : children) {
                try {
                    Instant childTime =
                            Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant();

                    if (childTime.isAfter(newest)) {
                        newest = childTime;
                    }
                } catch (IOException e) {
                    // Debug, not warn: with thousands of files a vanishing entry is normal, and
                    // the fallback (the parent's own timestamp) is the conservative answer anyway.
                    log.debug("Cleanup could not read the timestamp of {}: {}", child, e.getMessage());
                }
            }
        }

        return newest;
    }

    /** A configured storage path, absolute and normalized - the form every check compares against. */
    private Path absolute(String configuredPath) {
        return Paths.get(configuredPath).toAbsolutePath().normalize();
    }

    /**
     * One temporary storage area: where it lives, what its entries are named, and who deletes
     * them. Keeping the two areas as data means the scan, the age check and the path rules are
     * written once and applied identically to both.
     *
     * @param storageType human-readable name for the logs
     * @param root        the shared parent, which is never deleted
     * @param prefix      the name prefix the owning service gives its own entries
     * @param deleter     the owning service's recursive, self-validating delete
     */
    private record TemporaryStorageArea(
            String storageType,
            Path root,
            String prefix,
            Consumer<Path> deleter
    ) {
    }

}
