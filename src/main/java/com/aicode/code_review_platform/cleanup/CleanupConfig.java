package com.aicode.code_review_platform.cleanup;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * STEP 6.4 - settings for the temporary storage safety net.
 *
 * <p>Nothing here is hardcoded in Java: the values come from application.properties, are
 * overridden per profile, and are overridable per environment through APP_CLEANUP_* variables.
 * Durations are millisecond longs, matching the app.jwt.expiration-ms property that already
 * exists rather than introducing a second convention.
 */
@Configuration
@Getter
@Setter
public class CleanupConfig {

    /**
     * Whether the startup and scheduled cleanups actually delete anything. Set per profile - off
     * in dev, so a failed review leaves its checkout on disk to inspect, on in prod, where nothing
     * else reclaims volume space after a crash.
     */
    @Value("${app.cleanup.enabled}")
    private boolean enabled;

    /**
     * How long to wait between scheduled runs. Read directly from the property by the
     * {@code @Scheduled} annotation - this field exists so the value can be logged and asserted
     * on, and so the whole cleanup configuration is visible in one place.
     */
    @Value("${app.cleanup.interval-ms}")
    private long intervalMs;

    /**
     * How old temporary data has to be before cleanup may delete it. This is the only thing
     * standing between the safety net and a review that is still running, so it is deliberately
     * far longer than any review should take.
     */
    @Value("${app.cleanup.max-age-ms}")
    private long maxAgeMs;

}
