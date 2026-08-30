package com.aicode.code_review_platform.cleanup;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * STEP 6.4 - turns on Spring's own scheduler, which nothing had needed until the temporary
 * storage cleanup.
 *
 * <p>The default single-threaded task scheduler is kept on purpose: with one thread and
 * {@code fixedDelay}, a cleanup run that outlives its own interval simply delays the next one
 * instead of overlapping with it. No external scheduling framework is involved.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
