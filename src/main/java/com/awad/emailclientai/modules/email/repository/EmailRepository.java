package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.dto.response.EmailStatusStatsDto;
import com.awad.emailclientai.modules.email.entity.EmailEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailRepository extends JpaRepository<EmailEntity, Long>, JpaSpecificationExecutor<EmailEntity> {
    Optional<EmailEntity> findByMessageId(String messageId);
    
    List<EmailEntity> findByStatus(String status);

    List<EmailEntity> findBySnoozedUntilBeforeAndStatus(OffsetDateTime now, String status);
    
    @Query("SELECT e FROM EmailEntity e JOIN FETCH e.account WHERE e.account.id = :accountId ORDER BY e.kanbanOrder DESC, e.receivedDate DESC")
    List<EmailEntity> findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(@Param("accountId") Long accountId);

    @Query(value = "SELECT e.* FROM emails e WHERE e.account_id = :accountId AND " +
           "(word_similarity(:query, e.subject) > 0.1 OR word_similarity(:query, e.sender) > 0.1 OR " +
           "similarity(:query, e.subject) > 0.1 OR " +
           "LOWER(e.subject) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.sender) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY " +
           "  CASE WHEN LOWER(e.subject) = LOWER(:query) THEN 1 ELSE 2 END, " +
           "  GREATEST(word_similarity(:query, e.subject), similarity(:query, e.subject)) DESC " +
           "LIMIT 20", nativeQuery = true)
    List<EmailEntity> searchEmails(@Param("accountId") Long accountId, @Param("query") String query);

    @Query(value = "SELECT e.id, e.message_id, e.thread_id, e.gmail_message_id, e.uid, e.subject, e.sender, e.snippet, e.body, " +
           "e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.is_starred, e.has_attachments, e.account_id, a.email_address as account_email, " +
           "GREATEST(word_similarity(:query, e.subject), word_similarity(:query, e.sender)) AS relevance_score, e.summary_source " +
           "FROM emails e JOIN email_accounts a ON e.account_id = a.id WHERE e.account_id = :accountId AND " +
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

    @Query(value = "SELECT e.id, e.message_id, e.thread_id, e.gmail_message_id, e.uid, e.subject, e.sender, e.snippet, e.body, " +
           "e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.is_starred, e.has_attachments, e.account_id, a.email_address as account_email, " +
           "(e.embedding_768 <=> cast(:embedding as vector)) as distance, e.summary_source " +
           "FROM emails e JOIN email_accounts a ON e.account_id = a.id " +
           "WHERE e.account_id = :accountId AND e.embedding_768 IS NOT NULL AND (e.embedding_768 <=> cast(:embedding as vector)) < :threshold " +
           "ORDER BY distance LIMIT 10", nativeQuery = true)
    List<Object[]> findSimilarEmails768WithDistance(@Param("accountId") Long accountId, @Param("embedding") String embedding, @Param("threshold") double threshold);

    @Query(value = "SELECT e.id, e.message_id, e.thread_id, e.gmail_message_id, e.uid, e.subject, e.sender, e.snippet, e.body, " +
           "e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.is_starred, e.has_attachments, e.account_id, a.email_address as account_email, " +
           "(e.embedding_384 <=> cast(:embedding as vector)) as distance, e.summary_source " +
           "FROM emails e JOIN email_accounts a ON e.account_id = a.id " +
           "WHERE e.account_id = :accountId AND e.embedding_384 IS NOT NULL AND (e.embedding_384 <=> cast(:embedding as vector)) < :threshold " +
           "ORDER BY distance LIMIT 10", nativeQuery = true)
    List<Object[]> findSimilarEmails384WithDistance(@Param("accountId") Long accountId, @Param("embedding") String embedding, @Param("threshold") double threshold);

    @Query("SELECT e FROM EmailEntity e WHERE e.account.id = :accountId AND (e.embedding384 IS NULL AND e.embedding768 IS NULL) AND e.body IS NOT NULL")
    List<EmailEntity> findEmailsMissingEmbeddings(@Param("accountId") Long accountId, org.springframework.data.domain.Pageable pageable);

    @Query(value = "SELECT val, type, MAX(score) as max_score FROM (" +
            "SELECT subject AS val, 'subject' as type, similarity(subject, :prefix) AS score FROM emails " +
            "WHERE account_id = :accountId AND (subject % :prefix OR LOWER(subject) LIKE LOWER(CONCAT('%', :prefix, '%'))) " +
            "UNION ALL " +
            "SELECT sender AS val, 'sender' as type, similarity(sender, :prefix) AS score FROM emails " +
             "WHERE account_id = :accountId AND (sender % :prefix OR LOWER(sender) LIKE LOWER(CONCAT('%', :prefix, '%')))" +
            ") sub GROUP BY val, type ORDER BY max_score DESC LIMIT 10", nativeQuery = true)
    List<Object[]> findSuggestions(@Param("accountId") Long accountId, @Param("prefix") String prefix);
    @Query("SELECT new com.awad.emailclientai.modules.email.dto.response.EmailStatusStatsDto(e.status, COUNT(e)) " +
           "FROM EmailEntity e WHERE e.account.id = :accountId AND e.receivedDate >= :startDate GROUP BY e.status")
    List<EmailStatusStatsDto> countByStatusForAccount(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT TO_CHAR(received_date, 'YYYY-MM-DD') as date, COUNT(*) as count " +
           "FROM emails WHERE account_id = :accountId AND received_date >= :startDate " +
           "GROUP BY TO_CHAR(received_date, 'YYYY-MM-DD') ORDER BY date", nativeQuery = true)
    List<Object[]> getEmailTrend(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT sender, COUNT(*) as count FROM emails " +
           "WHERE account_id = :accountId AND received_date >= :startDate " +
           "GROUP BY sender ORDER BY count DESC LIMIT 10", nativeQuery = true)
    List<Object[]> getTopSenders(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT EXTRACT(DOW FROM received_date) as day, EXTRACT(HOUR FROM received_date) as hour, COUNT(*) as count " +
           "FROM emails WHERE account_id = :accountId AND received_date >= :startDate " +
           "GROUP BY day, hour ORDER BY day, hour", nativeQuery = true)
    List<Object[]> getDailyActivity(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);

    @Query("SELECT COUNT(e) FROM EmailEntity e WHERE e.account.id = :accountId AND e.receivedDate >= :startDate")
    long countByAccountId(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);
 
    @Query("SELECT COUNT(e) FROM EmailEntity e WHERE e.account.id = :accountId AND e.isRead = false AND e.receivedDate >= :startDate")
    long countUnreadByAccountId(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);
 
    @Query("SELECT COUNT(e) FROM EmailEntity e WHERE e.account.id = :accountId AND e.isStarred = true AND e.receivedDate >= :startDate")
    long countStarredByAccountId(@Param("accountId") Long accountId, @Param("startDate") LocalDateTime startDate);

    @Query("SELECT e FROM EmailEntity e WHERE e.account.id = :accountId AND " +
           "(e.body LIKE '%body {%' OR e.body LIKE '%.ie-browser%' OR e.body LIKE '%.mso-container%' OR e.body LIKE '%ExternalClass%')")
    List<EmailEntity> findCorruptedEmails(@Param("accountId") Long accountId);
}
