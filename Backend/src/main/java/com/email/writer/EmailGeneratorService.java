package com.email.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class EmailGeneratorService {
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.url:}")
    private String geminiApiUrl;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String geminiApiBaseUrl;

    @Value("${gemini.api.model:gemini-2.5-flash}")
    private String geminiModel;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    public EmailGeneratorService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    public String generateEmailReply(EmailRequest emailRequest) {
        validateRequest(emailRequest);

        String prompt = buildPrompt(emailRequest);
        Map<String, Object> requestBody = Map.of(
                "contents", new Object[]{
                        Map.of("parts", new Object[]{
                                Map.of("text", prompt)
                        })
                }
        );

        String response = webClient.post()
                .uri(buildGeminiUri())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        httpStatusCode -> httpStatusCode.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("Empty error response from Gemini API")
                                .map(errorBody -> new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Gemini API request failed: " + errorBody
                                ))
                )
                .bodyToMono(String.class)
                .block();

        return extractResponseContent(response);
    }

    private String extractResponseContent(String response) {
        try {
            if (!StringUtils.hasText(response)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini API returned an empty response.");
            }

            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode responseText = rootNode.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text");

            if (responseText.isMissingNode() || !StringUtils.hasText(responseText.asText())) {
                String apiErrorMessage = rootNode.path("error").path("message").asText();
                if (StringUtils.hasText(apiErrorMessage)) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini API error: " + apiErrorMessage);
                }

                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini API returned no candidate text.");
            }

            return responseText.asText().trim();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to parse Gemini API response: " + exception.getMessage(),
                    exception
            );
        }
    }

    private String buildPrompt(EmailRequest emailRequest) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a professional email reply for the following Content. Please do not generate a subject line. ");
        if (StringUtils.hasText(emailRequest.getTone())) {
            prompt.append("Use a ").append(emailRequest.getTone()).append(" tone. ");
        }
        prompt.append("\nOriginal email:\n").append(emailRequest.getEmailContent());
        return prompt.toString();
    }

    private void validateRequest(EmailRequest emailRequest) {
        if (emailRequest == null || !StringUtils.hasText(emailRequest.getEmailContent())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email content is required.");
        }

        if (!StringUtils.hasText(geminiApiKey)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Gemini API key is not configured.");
        }
    }

    private String buildGeminiUri() {
        if (StringUtils.hasText(geminiApiUrl)) {
            if (geminiApiUrl.contains("?key=") || !StringUtils.hasText(geminiApiKey)) {
                return geminiApiUrl;
            }

            return geminiApiUrl + geminiApiKey;
        }

        String normalizedBaseUrl = geminiApiBaseUrl.endsWith("/")
                ? geminiApiBaseUrl.substring(0, geminiApiBaseUrl.length() - 1)
                : geminiApiBaseUrl;

        return normalizedBaseUrl + "/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
    }
}
