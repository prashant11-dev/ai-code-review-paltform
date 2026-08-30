package com.aicode.code_review_platform.cleanup;

import com.aicode.code_review_platform.review.github.RepositoryConfig;
import com.aicode.code_review_platform.review.github.service.RepositoryCloneServiceImpl;
import com.aicode.code_review_platform.storage.FileStorageConfig;
import com.aicode.code_review_platform.storage.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STEP 6.4 - proves the cleanup actually wires up in a Spring context.
 *
 * <p>The full application context test cannot cover this: it needs a database. This one boots
 * only the cleanup beans, which is enough to catch the two mistakes that would otherwise only
 * surface at startup in production - a property placeholder that does not resolve, and a
 * scheduled method that never gets registered with the scheduler.
 */
class TemporaryStorageCleanupWiringTest {

    @TempDir
    Path tempRepositories;

    @TempDir
    Path uploads;

    private ApplicationContextRunner contextRunner() {

        return new ApplicationContextRunner()
                // The bare runner is not a Spring Boot application, so it does not install the
                // conversion service Boot normally registers. Without it FileStorageConfig cannot
                // turn "10MB" into a DataSize - a gap in the harness, not in the beans.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(
                        SchedulingConfig.class,
                        CleanupConfig.class,
                        RepositoryConfig.class,
                        FileStorageConfig.class,
                        RepositoryCloneServiceImpl.class,
                        FileUploadService.class,
                        TemporaryStorageCleanupService.class
                )
                .withPropertyValues(
                        "app.cleanup.enabled=true",
                        "app.cleanup.interval-ms=3600000",
                        "app.cleanup.max-age-ms=7200000",
                        "app.repository.temp-dir=" + tempRepositories,
                        "app.upload-dir=" + uploads,
                        "spring.servlet.multipart.max-file-size=10MB"
                );
    }

    @Test
    void theCleanupPropertiesBindFromConfiguration() {

        contextRunner().run(context -> {

            assertThat(context).hasNotFailed();

            CleanupConfig config = context.getBean(CleanupConfig.class);

            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getIntervalMs()).isEqualTo(3_600_000L);
            assertThat(config.getMaxAgeMs()).isEqualTo(7_200_000L);
        });
    }

    @Test
    void theScheduledCleanupIsRegisteredWithSpringsScheduler() {

        contextRunner().run(context -> {

            assertThat(context).hasNotFailed();

            // Registration proves both that @EnableScheduling is on and that the interval
            // placeholder resolved - an unresolvable one fails the context outright.
            ScheduledAnnotationBeanPostProcessor scheduler =
                    context.getBean(ScheduledAnnotationBeanPostProcessor.class);

            assertThat(scheduler.getScheduledTasks()).isNotEmpty();
        });
    }

    @Test
    void aMissingCleanupPropertyFailsFastRatherThanDefaultingInCode() {

        // No value is hardcoded in Java, so leaving the property out has to break loudly instead
        // of silently picking an interval or an age threshold of its own.
        contextRunner()
                .withPropertyValues("app.cleanup.max-age-ms=")
                .run(context -> assertThat(context).hasFailed());
    }
}
