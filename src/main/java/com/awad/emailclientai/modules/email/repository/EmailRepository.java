package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailRepository extends JpaRepository<EmailEntity, Long>, JpaSpecificationExecutor<EmailEntity> {
    Optional<EmailEntity> findByMessageId(String messageId);
    
    List<EmailEntity> findByStatus(String status);

    List<EmailEntity> findBySnoozedUntilBeforeAndStatus(LocalDateTime now, String status);
    
    List<EmailEntity> findByAccountId(Long accountId);

    @Query(value = "SELECT e.* FROM emails e WHERE e.account_id = :accountId AND " +
           "(word_similarity(:query, e.subject) > 0.3 OR word_similarity(:query, e.sender) > 0.3 OR " +
           "LOWER(e.subject) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.sender) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY GREATEST(word_similarity(:query, e.subject), word_similarity(:query, e.sender)) DESC " +
           "LIMIT 20", nativeQuery = true)
    List<EmailEntity> searchEmails(@Param("accountId") Long accountId, @Param("query") String query);

    @Query(value = "SELECT e.id, e.message_id, e.uid, e.subject, e.sender, e.snippet, e.body, " +
           "e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.has_attachments, e.account_id, " +
           "GREATEST(word_similarity(:query, e.subject), word_similarity(:query, e.sender)) AS relevance_score " +
           "FROM emails e WHERE e.account_id = :accountId AND " +
           "(word_similarity(:query, e.subject) > 0.3 OR word_similarity(:query, e.sender) > 0.3 OR " +
           "LOWER(e.subject) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.sender) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY relevance_score DESC " +
           "LIMIT 20", nativeQuery = true)
    List<Object[]> searchEmailsWithScore(@Param("accountId") Long accountId, @Param("query") String query);

    @Modifying
    @Query(value = "UPDATE emails SET embedding_768 = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding768(@Param("id") Long id, @Param("embedding") String embedding);

    @Modifying
    @Query(value = "UPDATE emails SET embedding_384 = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding384(@Param("id") Long id, @Param("embedding") String embedding);

    @Query(value = "SELECT e.id, e.message_id, e.uid, e.subject, e.sender, e.snippet, e.body, e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.has_attachments, e.account_id, NULL as embedding_768, NULL as embedding_384 FROM emails e WHERE e.embedding_768 IS NOT NULL AND (e.embedding_768 <=> cast(:embedding as vector)) < :threshold ORDER BY e.embedding_768 <=> cast(:embedding as vector) LIMIT 10", nativeQuery = true)
    List<EmailEntity> findSimilarEmails768(@Param("embedding") String embedding, @Param("threshold") double threshold);

    @Query(value = "SELECT e.id, e.message_id, e.uid, e.subject, e.sender, e.snippet, e.body, e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.has_attachments, e.account_id, NULL as embedding_768, NULL as embedding_384 FROM emails e WHERE e.embedding_384 IS NOT NULL AND (e.embedding_384 <=> cast(:embedding as vector)) < :threshold ORDER BY e.embedding_384 <=> cast(:embedding as vector) LIMIT 10", nativeQuery = true)
    List<EmailEntity> findSimilarEmails384(@Param("embedding") String embedding, @Param("threshold") double threshold);

    @Query(value = "SELECT val, MAX(score) as max_score FROM (" +
            "SELECT subject AS val, similarity(subject, :prefix) AS score FROM emails " +
            "WHERE subject % :prefix OR LOWER(subject) LIKE LOWER(CONCAT('%', :prefix, '%')) " +
            "UNION ALL " +
            "SELECT sender AS val, similarity(sender, :prefix) AS score FROM emails " +
            "WHERE sender % :prefix OR LOWER(sender) LIKE LOWER(CONCAT('%', :prefix, '%'))" +
            ") sub GROUP BY val ORDER BY max_score DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findSuggestions(@Param("prefix") String prefix);
}
