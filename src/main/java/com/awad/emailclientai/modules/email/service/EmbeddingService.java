package com.awad.emailclientai.modules.email.service;

import java.util.List;

public interface EmbeddingService {
    /**
     * Generates a vector embedding for the given text.
     * @param text The text to embed.
     * @return A list of floats representing the vector.
     */
    List<Float> generateEmbedding(String text);

    /**
     * @return The dimension of the vector produced by this service.
     */
    int getPreferredDimension();
}
