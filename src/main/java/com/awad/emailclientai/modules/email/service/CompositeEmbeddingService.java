package com.awad.emailclientai.modules.email.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Primary
public class CompositeEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(CompositeEmbeddingService.class);
    
    private final GeminiEmbeddingService geminiService;
    private final Optional<OnnxEmbeddingService> onnxService;

    public CompositeEmbeddingService(GeminiEmbeddingService geminiService, Optional<OnnxEmbeddingService> onnxService) {
        this.geminiService = geminiService;
        this.onnxService = onnxService;
    }

    @Override
    public List<Float> generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return geminiService.generateEmbedding(text);
        } catch (Exception e) {
            logger.warn("Gemini API failed, attempting local fallback: {}", e.getMessage());

            // Only attempt ONNX fallback if the service is present and the model was successfully loaded
            if (onnxService.isPresent() && onnxService.get().isAvailable()) {
                try {
                    return onnxService.get().generateEmbedding(text);
                } catch (Exception ex) {
                    logger.error("Both Gemini and Local ONNX embedding services failed", ex);
                    throw new RuntimeException("Embedding generation failed completely");
                }
            }

            logger.error("Gemini API failed and local ONNX model is either disabled or not available. No fallback.");
            throw new RuntimeException("Embedding generation failed - Gemini API error and ONNX model not loaded");
        }
    }

    @Override
    public int getPreferredDimension() {
        try {
            return geminiService.getPreferredDimension();
        } catch (Exception e) {
            if (onnxService.isPresent() && onnxService.get().isAvailable()) {
                return onnxService.get().getPreferredDimension();
            }
            // Default to Gemini's dimension (768) when neither service reports
            return 768;
        }
    }

}
