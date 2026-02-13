package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    private final EmailRepository emailRepository;
    private final EmbeddingService embeddingService;

    /**
     * Performs semantic search using vector embeddings.
     * @param query User's natural language query.
     * @return List of relevant emails.
     */
    @Transactional(readOnly = true)
    public List<EmailEntity> semanticSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            log.debug("Generating embedding for query: {}", query);
            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);
            
            // Convert List<Float> to pgvector string format: [0.1,0.2,...]
            String vectorString = "[" + queryEmbedding.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
            
            log.debug("Searching DB with vector (dim={})...", queryEmbedding.size());
            
            // Search the column matching the query embedding dimension
            int dimension = queryEmbedding.size();
            if (dimension == 768) {
                return emailRepository.findSimilarEmails768(vectorString, 0.5);
            } else if (dimension == 384) {
                return emailRepository.findSimilarEmails384(vectorString, 0.5);
            } else {
                log.warn("Unexpected query embedding dimension: {}", dimension);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Gets auto-suggestions for search bar.
     */
    @Transactional(readOnly = true)
    public List<String> getSuggestions(String prefix) {
        if (prefix == null || prefix.trim().length() < 2) {
            return Collections.emptyList();
        }
        return emailRepository.findSuggestions(prefix);
    }
}
