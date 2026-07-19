package com.aicode.code_review_platform.review.github;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
public class RepositoryConfig {

    @Value("${app.repository.temp-dir}")
    private String tempDirectory;

}
