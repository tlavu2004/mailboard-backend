package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
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
    
    List<EmailEntity> findByStatus(EmailStatus status);

    List<EmailEntity> findBySnoozedUntilBeforeAndStatus(LocalDateTime now, EmailStatus status);
    
    List<EmailEntity> findByAccountId(Long accountId);

    @Query("SELECT e FROM EmailEntity e WHERE e.account.id = :accountId AND " +
           "(LOWER(e.subject) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.sender) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<EmailEntity> searchEmails(@Param("accountId") Long accountId, @Param("query") String query);

    @Modifying
    @Query(value = "UPDATE emails SET embedding = cast(:embedding as vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    @Query(value = "SELECT e.id, e.message_id, e.uid, e.subject, e.sender, e.snippet, e.body, e.status, e.received_date, e.snoozed_until, e.summary, e.is_read, e.has_attachments, e.account_id, NULL as embedding FROM emails e WHERE e.embedding IS NOT NULL ORDER BY e.embedding <=> cast(:embedding as vector) LIMIT 10", nativeQuery = true)
    List<EmailEntity> findSimilarEmails(@Param("embedding") String embedding);

    @Query(value = "SELECT DISTINCT subject FROM emails WHERE LOWER(subject) LIKE LOWER(CONCAT(:prefix, '%')) " +
            "UNION SELECT DISTINCT sender FROM emails WHERE LOWER(sender) LIKE LOWER(CONCAT(:prefix, '%')) " +
            "LIMIT 10", nativeQuery = true)
    List<String> findSuggestions(@Param("prefix") String prefix);
}
