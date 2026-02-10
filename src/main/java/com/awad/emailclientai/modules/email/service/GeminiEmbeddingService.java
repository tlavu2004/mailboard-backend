package com.awad.emailclientai.modules.email.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Service
public class GeminiEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEmbeddingService.class);
    private final RestClient restClient;
    
    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model:text-embedding-004}")
    private String model;

    public GeminiEmbeddingService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        logger.debug("Generating embedding via Gemini for text length: {}", text.length());
        
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":embedContent?key=" + apiKey;

        try {
            // Updated payload structure for newer Gemini models
            // outputDimensionality=384 to match local model
            Map<String, Object> request = Map.of(
                "model", "models/" + model,
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                ),
                "outputDimensionality", 384
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("embedding")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> embeddingMap = (Map<String, Object>) response.get("embedding");
                @SuppressWarnings("unchecked")
                List<Float> values = (List<Float>) embeddingMap.get("values");
                return values;
            }

            throw new RuntimeException("Empty response from Gemini API");
        } catch (Exception e) {
            logger.error("Failed to generate embedding via Gemini", e);
            throw new RuntimeException("Gemini API Error: " + e.getMessage());
        }
    }
}
