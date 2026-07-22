package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.provider.GeminiProvider;
import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
import com.aicode.code_review_platform.review.github.dto.CodeFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIReviewServiceImpl implements AIReviewService {


    @Autowired
    private GeminiProvider geminiProvider;

    @Autowired
    private final ObjectMapper objectMapper;

    @Override
    public AIReviewResult review(
            ReviewContext context
    ) {

        log.info(
                "Starting AI review for {} file(s)",
                context.getFiles().size()
        );

        String prompt = buildPrompt(context);

        String result = geminiProvider.review(prompt);
        log.debug("Raw Gemini response: {}", result);

        result = cleanJson(result);

        AIReviewResult reviewResult = parseResponse(result);

        log.info("AI review completed");

        return reviewResult;

    }

    private String buildPrompt(ReviewContext context) {

        StringBuilder filesSection = new StringBuilder();

        for (CodeFile file : context.getFiles()) {
            filesSection
                    .append("File:\n")
                    .append(file.getRelativePath() != null ? file.getRelativePath() : file.getFileName())
                    .append("\n\n")
                    .append(file.getContent())
                    .append("\n\n-------------------------\n\n");
        }

        return """
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
                """.formatted(filesSection);
    }

    private AIReviewResult parseResponse(String res) {
        try {
            return objectMapper.readValue(res, AIReviewResult.class);
        } catch (RuntimeException e) {
            log.error("Failed to parse AI review response as JSON. Cleaned response: {}", res, e);
            throw new RuntimeException("Failed to parse AI review response", e);
        }
    }

    private String cleanJson(String response) {

        int start = response.indexOf("{");

        int end = response.lastIndexOf("}");

        if (start == -1 || end == -1 || end < start) {
            log.error("Gemini response did not contain a JSON object. Raw response: {}", response);
            throw new RuntimeException("AI response did not contain valid JSON");
        }

        return response.substring(
                start,
                end + 1
        );
    }


}
