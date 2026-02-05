package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailRepository extends JpaRepository<EmailEntity, Long> {
    Optional<EmailEntity> findByMessageId(String messageId);
    
    List<EmailEntity> findByStatus(EmailStatus status);
    
    List<EmailEntity> findByAccountId(Long accountId);
}
