package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.Email;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailRepository extends JpaRepository<Email, Long> {
    Page<Email> findByMailboxId(Long mailboxId, Pageable pageable);
}
