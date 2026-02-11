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
            
            log.debug("Searching DB with vector...");
            // Use local variable for limit (e.g. 10 results)
            // Note: The limit is hardcoded in the native query in Repository for now, 
            // but we passed it as param? 
            // Wait, my Repository method signature was: findSimilarEmails(@Param("embedding") List<Float> embedding);
            // I removed limit param in valid replacement or did I?
            // Let's verify repository method signature call.
            return emailRepository.findSimilarEmails(queryEmbedding);
        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return Collections.emptyList();
        }
    }

}
