package com.awad.emailclientai.modules.email.repository;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing EmailAccount entities.
 */
@Repository
public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {

    /**
     * Find all email accounts linked to a specific user.
     */
    List<EmailAccount> findByUserId(Long userId);

    /**
     * Find all active email accounts for a user.
     */
    List<EmailAccount> findByUserIdAndActiveTrue(Long userId);

    /**
     * Find a specific email account by user and email address.
     */
    Optional<EmailAccount> findByUserIdAndEmailAddress(Long userId, String emailAddress);

    /**
     * Find an email account by its email address.
     */
    Optional<EmailAccount> findByEmailAddress(String emailAddress);

    /**
     * Check if an email account already exists for a user.
     */
    boolean existsByUserIdAndEmailAddress(Long userId, String emailAddress);

    /**
     * Find email account by ID and user ID (for security - ensures user owns the account).
     */
    Optional<EmailAccount> findByIdAndUserId(Long id, Long userId);
}
