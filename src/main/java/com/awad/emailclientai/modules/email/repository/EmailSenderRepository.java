package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailSender;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EmailSenderRepository extends JpaRepository<EmailSender, Long> {
    Optional<EmailSender> findByEmail(String email);
    List<EmailSender> findAllByOrderByBestKnownNameAsc();
}
