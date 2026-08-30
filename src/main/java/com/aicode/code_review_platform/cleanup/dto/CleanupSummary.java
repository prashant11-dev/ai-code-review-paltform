package com.aicode.code_review_platform.cleanup.dto;

/**
 * STEP 6.4 - what one cleanup run did, aggregated across both storage areas.
 *
 * @param scanned  top-level entries looked at
 * @param deleted  stale entries that are gone
 * @param retained entries left alone - too new, or not ours to delete
 * @param failed   entries that could not be read or could not be fully deleted
 */
public record CleanupSummary(int scanned, int deleted, int retained, int failed) {

    public static CleanupSummary empty() {
        return new CleanupSummary(0, 0, 0, 0);
    }

    /** Folds another area's counts into this one. */
    public CleanupSummary plus(CleanupSummary other) {
        return new CleanupSummary(
                scanned + other.scanned,
                deleted + other.deleted,
                retained + other.retained,
                failed + other.failed
        );
    }
}
