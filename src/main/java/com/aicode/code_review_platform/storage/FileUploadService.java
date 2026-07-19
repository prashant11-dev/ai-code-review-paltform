package com.aicode.code_review_platform.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadService {

    @Autowired
    private FileStorageConfig  fileStorageConfig;

    public Path saveFile(MultipartFile file)
            throws IOException {

        Path uploadPath =
                Paths.get(fileStorageConfig.getUploadDir());

        Files.createDirectories(uploadPath);

        String fileName =
                System.currentTimeMillis()
                        + "_"
                        + file.getOriginalFilename();

        Path target =
                uploadPath.resolve(fileName);

        Files.copy(
                file.getInputStream(),
                target,
                StandardCopyOption.REPLACE_EXISTING
        );

        return target;
    }

}
