package com.aicode.code_review_platform.common.utils;

import com.aicode.code_review_platform.storage.FileStorageConfig;
import com.aicode.code_review_platform.storage.exception.FileValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STEP 6.3 - the checks an upload has to pass before anything is written to the uploads volume.
 */
class FileValidatorTest {

    private FileValidator fileValidator;

    @BeforeEach
    void setUp() {

        FileStorageConfig fileStorageConfig = new FileStorageConfig();
        fileStorageConfig.setUploadDir("uploads");
        fileStorageConfig.setMaxFileSize(DataSize.ofKilobytes(1));

        fileValidator = new FileValidator();
        ReflectionTestUtils.setField(fileValidator, "fileStorageConfig", fileStorageConfig);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UserService.java", "app.js", "types.ts", "Widget.jsx", "Widget.tsx", "main.py",
            // Case is not part of the rule: the extension decides the language, not the shift key.
            "Main.JAVA"
    })
    void acceptsEverySupportedSourceType(String fileName) {

        assertThatCode(() -> fileValidator.validate(file(fileName, "code")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAMissingFile() {

        assertThatThrownBy(() -> fileValidator.validate(null))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("missing or empty");
    }

    @Test
    void rejectsAnEmptyFile() {

        assertThatThrownBy(() -> fileValidator.validate(file("Main.java", "")))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("missing or empty");
    }

    @Test
    void rejectsAMissingFileName() {

        assertThatThrownBy(() -> fileValidator.validate(
                new MockMultipartFile("file", null, "text/plain", "code".getBytes())
        )).isInstanceOf(FileValidationException.class)
                .hasMessageContaining("name is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../something.java",
            "../Main.java",
            "..",
            ".",
            "dir/Main.java",
            "/etc/passwd.java",
            "..\\..\\Windows\\evil.java",
            "C:Main.java",
            "Main.java:stream"
    })
    void rejectsFileNamesCarryingPathComponents(String fileName) {

        assertThatThrownBy(() -> fileValidator.validate(file(fileName, "code")))
                .isInstanceOf(FileValidationException.class);
    }

    @Test
    void rejectsAFileLargerThanTheConfiguredLimit() {

        // The limit is the same one Spring applies to the multipart request itself.
        String tooBig = "x".repeat(1025);

        assertThatThrownBy(() -> fileValidator.validate(file("Main.java", tooBig)))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("exceeds the limit");
    }

    @Test
    void acceptsAFileExactlyOnTheLimit() {

        assertThatCode(() -> fileValidator.validate(file("Main.java", "x".repeat(1024))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "archive.zip", "Makefile", "Main.", "script.sh"})
    void rejectsUnsupportedFileTypes(String fileName) {

        assertThatThrownBy(() -> fileValidator.validate(file(fileName, "code")))
                .isInstanceOf(FileValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    private MockMultipartFile file(String originalFilename, String content) {

        return new MockMultipartFile("file", originalFilename, "text/plain", content.getBytes());
    }
}
