package com.aicode.code_review_platform.review.github.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodeFile {

    private String fileName;

    private String relativePath;

    private String language;

    private String content;


}
