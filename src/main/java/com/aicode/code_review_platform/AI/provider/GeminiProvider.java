package com.aicode.code_review_platform.AI.provider;

import com.aicode.code_review_platform.AI.GeminiConfig;
import com.aicode.code_review_platform.AI.dto.gemini.Content;
import com.aicode.code_review_platform.AI.dto.gemini.GeminiRequest;
import com.aicode.code_review_platform.AI.dto.gemini.Part;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiProvider implements AiProvider {

    private final RestTemplate restTemplate;

    private final GeminiConfig geminiConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String review(String prompt) {

        try {

            GeminiRequest request =
                    new GeminiRequest(
                            List.of(
                                    new Content(
                                            List.of(
                                                    new Part(prompt)
                                            )
                                    )
                            )
                    );

            String url =
                    geminiConfig.getApiUrl()
                            + "?key="
                            + geminiConfig.getApiKey();

            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            url,
                            request,
                            String.class
                    );

            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("candidates")
                       .path(0)
                       .path("content")
                       .path("parts")
                       .path(0)
                       .path("text")
                       .asText();

        } catch (Exception ex) {

            log.error(
                    "Gemini request failed",
                    ex
            );

            throw new RuntimeException(
                    "Failed to generate AI review"
            );
        }
    }
}