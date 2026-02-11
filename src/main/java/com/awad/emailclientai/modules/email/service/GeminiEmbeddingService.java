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
        logger.debug("Generating embedding via Gemini for text length: {}", text.length());
        
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":embedContent";

        try {
            // text-embedding-004 supports outputDimensionality
            // DB is now 768 (V5.1), so we request 768.
            Map<String, Object> request = Map.of(
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                ),
                "outputDimensionality", 768
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .header("x-goog-api-key", apiKey)
                    .body(request)
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
                    logger.info("Generated embedding with size: {}", values.size());
                    return values;
                } else {
                    logger.error("Response 'embedding' map is missing 'values': {}", embeddingMap);
                }
            } else {
                logger.error("Response is null or missing 'embedding' key: {}", response);
            }

            throw new RuntimeException("Empty or invalid response from Gemini API");
        } catch (Exception e) {
            logger.error("Failed to generate embedding via Gemini", e);
            throw new RuntimeException("Gemini API Error: " + e.getMessage());
        }
    }
}
