package com.awad.emailclientai.modules.email.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import jakarta.annotation.PostConstruct;

@Service
public class GeminiEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEmbeddingService.class);
    private final RestClient restClient;
    
    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.embedding-model:gemini-embedding-001}")
    private String model;

    public GeminiEmbeddingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @PostConstruct
    public void init() {
        String maskedKey = (apiKey != null && apiKey.length() > 4) 
            ? "..." + apiKey.substring(apiKey.length() - 4) 
            : "NULL/SHORT";
        logger.info("GeminiEmbeddingService initialized with API Key: {}", maskedKey);
        logger.info("Gemini Model: {}", model);
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        int maxRetries = 3;
        int retryDelay = 2000; // ms

        for (int i = 0; i <= maxRetries; i++) {
            try {
                return generateEmbeddingInternal(text);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("429") && i < maxRetries) {
                    logger.warn("Gemini API rate limit hit (429). Retrying in {}ms... (Attempt {}/{})", 
                        retryDelay, i + 1, maxRetries);
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    retryDelay *= 2; // Exponential backoff
                    continue;
                }
                logger.error("Failed to generate embedding via Gemini after {} retries", i, e);
                throw new RuntimeException("Gemini API Error: " + e.getMessage());
            }
        }
        throw new RuntimeException("Gemini API Error: Maximum retries exceeded");
    }

    private List<Float> generateEmbeddingInternal(String text) {
        logger.debug("Generating embedding via Gemini for text length: {}", text.length());
        
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":embedContent";

        // text-embedding-004 supports outputDimensionality
        // DB is now 768 (V5.1), so we request 768.
        Map<String, Object> requestBody = Map.of(
            "content", Map.of(
                "parts", List.of(Map.of("text", text))
            ),
            "outputDimensionality", 768
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response != null && response.containsKey("embedding")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> embeddingMap = (Map<String, Object>) response.get("embedding");
            if (embeddingMap != null && embeddingMap.containsKey("values")) {
                @SuppressWarnings("unchecked")
                List<Number> rawValues = (List<Number>) embeddingMap.get("values");
                List<Float> values = rawValues.stream()
                    .map(Number::floatValue)
                    .collect(java.util.stream.Collectors.toList());
                logger.debug("Generated embedding with size: {}", values.size());
                return values;
            }
        }

        throw new RuntimeException("Empty or invalid response from Gemini API");
    }

    @Override
    public int getPreferredDimension() {
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("dummy-api-key")) {
            throw new RuntimeException("Gemini API key is not configured");
        }
        return 768;
    }
}
