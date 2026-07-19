package com.aicode.code_review_platform.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
public class FileStorageConfig {

    @Value("${app.upload-dir}")
    private String uploadDir;

}
