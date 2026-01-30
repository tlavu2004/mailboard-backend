package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.Mailbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailboxRepository extends JpaRepository<Mailbox, Long> {
    List<Mailbox> findByUserId(Long userId);

    Optional<Mailbox> findByUserIdAndName(Long userId, String name);
}
