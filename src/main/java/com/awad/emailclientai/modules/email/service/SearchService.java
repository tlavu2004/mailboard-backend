package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.dto.response.SemanticSearchResponse;
import com.awad.emailclientai.modules.email.dto.response.SuggestionDto;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;




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
    public SemanticSearchResponse semanticSearch(Long accountId, String query) {
        log.info("Starting semantic search for accountId: {}, query: '{}'", accountId, query);
        if (query == null || query.trim().isEmpty()) {
            return SemanticSearchResponse.builder()
                .results(Collections.emptyList())
                .query(query)
                .total(0)
                .build();
        }

        try {
            log.debug("Generating embedding for query: {}", query);
            List<Float> queryEmbedding = embeddingService.generateEmbedding(query);
            
            // Convert List<Float> to pgvector string format: [0.1,0.2,...]
            String vectorString = "[" + queryEmbedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
            
            // Search the column matching the query embedding dimension
            int dimension = queryEmbedding.size();
            List<Object[]> rows;
            if (dimension == 768) {
                rows = emailRepository.findSimilarEmails768WithDistance(accountId, vectorString, 0.9);
            } else if (dimension == 384) {
                rows = emailRepository.findSimilarEmails384WithDistance(accountId, vectorString, 0.9);
            } else {
                log.warn("Unexpected query embedding dimension: {}", dimension);
                rows = Collections.emptyList();
            }

            if (rows.isEmpty()) {
                log.info("Semantic search yielded 0 results for: {}", query);
            } else {
                log.info("Semantic search found {} results for: {}", rows.size(), query);
                return SemanticSearchResponse.builder()
                    .results(rows.stream()
                        .map(row -> SemanticSearchResponse.SearchResultItem.builder()
                            .email(mapRowToDto(row))
                            .score(Math.max(0, 1.0 - ((Number) row[18]).doubleValue()))
                            .build())
                        .collect(Collectors.toList()))
                    .query(query)
                    .total(rows.size())
                    .build();
            }

            // Fallback to fuzzy search if vector search found nothing
            if (rows.isEmpty()) {
                log.info("Falling back to fuzzy search for: {}", query);
                List<Object[]> fuzzyRows = emailRepository.searchEmailsWithScore(accountId, query);
                log.info("Fuzzy search found {} results for: {}", fuzzyRows.size(), query);
                
                return SemanticSearchResponse.builder()
                    .results(fuzzyRows.stream()
                        .map(row -> SemanticSearchResponse.SearchResultItem.builder()
                            .email(mapRowToDto(row))
                            .score(((Number) row[18]).doubleValue())
                            .build())
                        .collect(Collectors.toList()))
                    .query(query)
                    .total(fuzzyRows.size())
                    .build();
            }
            
            // Final fallback (should not be reached)
            return SemanticSearchResponse.builder()
                .results(Collections.emptyList())
                .query(query)
                .total(0)
                .build();
        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return SemanticSearchResponse.builder()
                .results(Collections.emptyList())
                .query(query)
                .total(0)
                .build();
        }
    }

    /**
     * Gets auto-suggestions for search bar.
     */
    @Transactional(readOnly = true)
    public List<SuggestionDto> getSuggestions(Long accountId, String prefix) {
        if (prefix == null || prefix.trim().length() < 2) {
            return Collections.emptyList();
        }
        List<Object[]> rows = emailRepository.findSuggestions(accountId, prefix);
        return rows.stream()
                .map(row -> SuggestionDto.builder()
                        .text((String) row[0])
                        .type((String) row[1])
                        .relevanceScore(Math.round(((Number) row[2]).doubleValue() * 100.0) / 100.0)
                        .build())
                .collect(Collectors.toList());
    }

    private EmailEntityDto mapRowToDto(Object[] row) {
        LocalDateTime receivedDate = null;
        if (row[10] != null) {
            if (row[10] instanceof Timestamp) {
                receivedDate = ((Timestamp) row[10]).toLocalDateTime();
            } else if (row[10] instanceof LocalDateTime) {
                receivedDate = (LocalDateTime) row[10];
            }
        }

        OffsetDateTime snoozedUntil = null;
        if (row[11] != null) {
            if (row[11] instanceof Timestamp) {
                snoozedUntil = ((Timestamp) row[11]).toInstant().atOffset(ZoneOffset.UTC);
            } else if (row[11] instanceof OffsetDateTime) {
                snoozedUntil = (OffsetDateTime) row[11];
            }
        }

        return EmailEntityDto.builder()
                .id(((Number) row[0]).longValue())
                .messageId((String) row[1])
                .threadId((String) row[2])
                .gmailMessageId((String) row[3])
                .uid(row[4] != null ? ((Number) row[4]).longValue() : null)
                .subject((String) row[5])
                .sender((String) row[6])
                .snippet((String) row[7])
                .body((String) row[8])
                .status((String) row[9])
                .receivedDate(receivedDate)
                .receivedAt(receivedDate != null ? receivedDate.atZone(ZoneId.systemDefault()).toInstant().toString() : null)
                .snoozedUntil(snoozedUntil)
                .summary((String) row[12])
                .isRead(row[13] != null && (Boolean) row[13])
                .isStarred(row[14] != null && (Boolean) row[14])
                .hasAttachments(row[15] != null && (Boolean) row[15])
                .accountEmail((String) row[17])
                .summarySource(row[19] != null ? row[19].toString() : null)
                .build();
    }
}