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
    private String apiKeysConfig;

    private String[] apiKeys;
    private int currentKeyIndex = 0;

    @Value("${gemini.embedding-model:text-embedding-004}")
    private String model;

    public GeminiEmbeddingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @PostConstruct
    public void init() {
        if (apiKeysConfig != null && !apiKeysConfig.isEmpty()) {
            apiKeys = apiKeysConfig.split(",");
            for (int i = 0; i < apiKeys.length; i++) {
                apiKeys[i] = apiKeys[i].trim();
            }
        } else {
            apiKeys = new String[0];
        }

        logger.info("GeminiEmbeddingService initialized with {} API Keys.", apiKeys.length);
        logger.info("Gemini Model: {}", model);
    }

    private String getNextApiKey() {
        if (apiKeys.length == 0) return null;
        String key = apiKeys[currentKeyIndex];
        currentKeyIndex = (currentKeyIndex + 1) % apiKeys.length;
        return key;
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
        String currentKey = getNextApiKey();

        if (currentKey == null) {
            throw new RuntimeException("No Gemini API keys configured");
        }

        // DB expects 768 dimensions.
        // text-embedding-004 supports explicit outputDimensionality.
        // gemini-embedding-001 does NOT support it and defaults to 768 anyway.
        Map<String, Object> contentMap = Map.of(
            "parts", List.of(Map.of("text", text))
        );

        Map<String, Object> requestBody;
        if ("text-embedding-004".equals(model)) {
            requestBody = Map.of(
                "content", contentMap,
                "outputDimensionality", 768
            );
        } else {
            requestBody = Map.of(
                "content", contentMap
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(url)
                .header("x-goog-api-key", currentKey)
                .body(requestBody)
                .retrieve()
                .body(Map.class);
        
        // ... rest of validation logic ...

        if (response != null && response.containsKey("embedding")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> embeddingMap = (Map<String, Object>) response.get("embedding");
            if (embeddingMap != null && embeddingMap.containsKey("values")) {
                @SuppressWarnings("unchecked")
                List<Number> rawValues = (List<Number>) embeddingMap.get("values");
                List<Float> values = rawValues.stream()
                    .map(Number::floatValue)
                    .collect(java.util.stream.Collectors.toList());
                
                // Handle Matryoshka embeddings (e.g., text-embedding-004 returns 3072 by default)
                // We truncate to 768 to fit our DB and maintain high search quality.
                if (values.size() == 3072) {
                    logger.debug("Truncating 3072-dim embedding to 768-dim (Matryoshka)");
                    values = values.subList(0, 768);
                    normalize(values);
                }

                logger.debug("Generated embedding with size: {}", values.size());
                return values;
            }
        }

        throw new RuntimeException("Empty or invalid response from Gemini API");
    }

    private void normalize(List<Float> vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += v * v;
        }
        float magnitude = (float) Math.sqrt(sumSquares);
        if (magnitude > 1e-6) {
            for (int i = 0; i < vector.size(); i++) {
                vector.set(i, vector.get(i) / magnitude);
            }
        }
    }

    @Override
    public int getPreferredDimension() {
        if (apiKeys.length == 0) {
            throw new RuntimeException("Gemini API key is not configured");
        }
        return 768;
    }
}
