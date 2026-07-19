package com.aicode.code_review_platform.common.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Component
public class FileValidator {

    private static final Set<String> ALLOWED = Set.of("java", "js", "ts", "jsx", "tsx", "py");

    public void validate(MultipartFile file) {

        String name = file.getOriginalFilename();

        String extension = name.substring(name.lastIndexOf(".") + 1);

        if (!ALLOWED.contains(extension)) {

            throw new RuntimeException("Unsupported file type");
        }
    }

}
