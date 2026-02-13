package com.awad.emailclientai.modules.email.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Primary
public class CompositeEmbeddingService implements EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(CompositeEmbeddingService.class);
    
    private final GeminiEmbeddingService geminiService;
    private final OnnxEmbeddingService onnxService;

    public CompositeEmbeddingService(GeminiEmbeddingService geminiService, OnnxEmbeddingService onnxService) {
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
            try {
                return onnxService.generateEmbedding(text);
            } catch (Exception ex) {
                logger.error("Both Gemini and Local embedding services failed", ex);
                throw new RuntimeException("Embedding generation failed completely");
            }
        }
    }
    @Override
    public int getPreferredDimension() {
        try {
            // Check if Gemini is working
            return geminiService.getPreferredDimension();
        } catch (Exception e) {
            // Fallback to ONNX dimension
            return onnxService.getPreferredDimension();
        }
    }
}
