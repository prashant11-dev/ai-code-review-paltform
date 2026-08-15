package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.review.github.dto.CodeFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class PromptBuilderServiceImpl implements PromptBuilderService {

    private static final String FILE_SEPARATOR = "\n\n-------------------------\n\n";

    private static final String PROMPT_TEMPLATE = """
            You are an expert software engineer.

            Review the following source file(s).

            %s
            Return ONLY valid JSON matching this exact structure.
            Every array must contain plain strings only, not objects.

            {
              "score": 85,
              "summary": "Brief overall summary of the code.",
              "bugs": ["Describe bug 1 as a plain string", "Describe bug 2 as a plain string"],
              "securityIssues": ["Describe security issue as a plain string"],
              "performanceIssues": ["Describe performance issue as a plain string"],
              "suggestions": ["Describe suggestion as a plain string"]
            }

            Rules:
            - score is an integer from 0 to 100
            - All array elements must be plain strings, never objects
            - Use empty arrays [] when there are no items
            - Do not include markdown
            - Do not include explanations outside the JSON
            - Return JSON only
            """;

    @Override
    public String buildPrompt(List<CodeFile> files) {

        if (files == null || files.isEmpty()) {
            // A prompt with no code in it would still get a scored answer back from the model,
            // which is worse than failing: it would look like a successful review of nothing.
            throw new IllegalArgumentException("Cannot build a review prompt from an empty file list");
        }

        StringBuilder filesSection = new StringBuilder();

        for (CodeFile file : files) {
            appendFile(filesSection, file);
        }

        String prompt = PROMPT_TEMPLATE.formatted(filesSection);

        log.debug("Built review prompt for {} file(s), {} characters", files.size(), prompt.length());

        return prompt;
    }

    private void appendFile(StringBuilder filesSection, CodeFile file) {

        // Name and path are both given: the name is what the model quotes in its findings, the
        // path is what makes those findings locatable in the repository.
        filesSection
                .append("File: ")
                .append(file.getFileName() != null ? file.getFileName() : "unknown")
                .append("\nPath: ")
                .append(resolvePath(file))
                .append("\nLanguage: ")
                .append(file.getLanguage() != null ? file.getLanguage() : "UNKNOWN")
                .append("\n\n")
                .append(file.getContent() != null ? file.getContent() : "")
                .append(FILE_SEPARATOR);
    }

    private String resolvePath(CodeFile file) {

        // Files submitted as raw text or a single upload have no repository-relative path; fall
        // back to the name so the section never renders a bare "null".
        if (file.getRelativePath() != null) {
            return file.getRelativePath();
        }

        return file.getFileName() != null ? file.getFileName() : "unknown";
    }

}
