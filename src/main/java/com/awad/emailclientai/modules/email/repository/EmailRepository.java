package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailRepository extends JpaRepository<EmailEntity, Long> {
    Optional<EmailEntity> findByMessageId(String messageId);
    
    List<EmailEntity> findByStatus(EmailStatus status);

    List<EmailEntity> findBySnoozedUntilBeforeAndStatus(LocalDateTime now, EmailStatus status);
    
    List<EmailEntity> findByAccountId(Long accountId);

    @Query("SELECT e FROM EmailEntity e WHERE e.account.id = :accountId AND " +
           "(LOWER(e.subject) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.sender) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<EmailEntity> searchEmails(@Param("accountId") Long accountId, @Param("query") String query);
}
