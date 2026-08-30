package com.aicode.code_review_platform.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
@Getter
@Setter
public class FileStorageConfig {

    /**
     * Upload root. Relative in the default configuration, which resolves to /app/uploads in the
     * container (WORKDIR is /app) - the path the uploads_data volume is mounted on.
     */
    @Value("${app.upload-dir}")
    private String uploadDir;

    /**
     * STEP 6.3 - the size ceiling the validator enforces. Deliberately bound to the multipart
     * limit that already exists rather than a second, independently drifting property: Spring
     * rejects anything larger before the controller is reached, so a different number here would
     * only ever be wrong in one direction or the other.
     */
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

}
