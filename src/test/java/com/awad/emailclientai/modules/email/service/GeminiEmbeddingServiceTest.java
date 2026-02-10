package com.awad.emailclientai.modules.email.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GeminiEmbeddingServiceTest {

    @Autowired
    private GeminiEmbeddingService geminiEmbeddingService;

    @Test
    @Disabled("Requires valid API Key")
    void generateEmbedding() {
        List<Float> embedding = geminiEmbeddingService.generateEmbedding("Hello world");
        assertNotNull(embedding);
        assertEquals(384, embedding.size(), "Embedding dimensions should be 384");
        System.out.println("Generated embedding: " + embedding.subList(0, 5) + "...");
    }
}
