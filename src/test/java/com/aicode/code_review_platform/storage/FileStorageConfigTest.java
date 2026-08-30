package com.aicode.code_review_platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STEP 6.3 - proves the upload configuration actually binds.
 *
 * <p>Boots a minimal Spring application containing nothing but {@link FileStorageConfig}, so the
 * property binding is exercised the way the real application does it without needing a database.
 * The size limit is a DataSize bound from a string property, which is the part worth guarding: a
 * conversion failure here would stop the container from starting at all.
 */
class FileStorageConfigTest {

    @Test
    void bindsTheUploadDirectoryAndTheMultipartSizeLimit() {

        try (ConfigurableApplicationContext context = boot(
                "app.upload-dir=/app/uploads",
                "spring.servlet.multipart.max-file-size=10MB"
        )) {
            FileStorageConfig config = context.getBean(FileStorageConfig.class);

            assertThat(config.getUploadDir()).isEqualTo("/app/uploads");
            assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
            assertThat(config.getMaxFileSize().toBytes()).isEqualTo(10L * 1024 * 1024);
        }
    }

    @Test
    void theShippedDefaultsResolveToTheDockerVolumeMountPoint() {

        // No overrides: what application.properties actually ships is a relative directory, which
        // resolves against the container WORKDIR (/app) to the path uploads_data is mounted on.
        try (ConfigurableApplicationContext context = boot()) {

            FileStorageConfig config = context.getBean(FileStorageConfig.class);

            assertThat(config.getUploadDir()).isEqualTo("uploads");
            assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
        }
    }

    /**
     * Overrides are passed as command line arguments rather than default properties, because
     * default properties sit below application.properties and would never win.
     */
    private ConfigurableApplicationContext boot(String... properties) {

        String[] arguments = new String[properties.length];
        for (int i = 0; i < properties.length; i++) {
            arguments[i] = "--" + properties[i];
        }

        return new SpringApplicationBuilder(FileStorageConfig.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .run(arguments);
    }
}
