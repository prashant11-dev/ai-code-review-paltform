package com.aicode.code_review_platform.AI.service;

import com.aicode.code_review_platform.AI.provider.GeminiProvider;
import com.aicode.code_review_platform.AI.dto.AIReviewResult;
import com.aicode.code_review_platform.AI.dto.ReviewContext;
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

    /**
     * MILESTONE 3 - prompt rendering was extracted into its own service. This class keeps the
     * {@code buildPrompt} method so existing callers are unaffected, but it now delegates: the
     * chunk consumer and the synchronous path must produce byte-identical prompts.
     */
    private final PromptBuilderService promptBuilderService;

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

    @Override
    public String buildPrompt(ReviewContext context) {
        return promptBuilderService.buildPrompt(context.getFiles());
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
