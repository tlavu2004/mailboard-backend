package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.MailMessageDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSyncService {

    private final ImapService imapService;
    private final EmailRepository emailRepository;
    private final EmailAccountRepository emailAccountRepository;
    private final EmbeddingService embeddingService;

    /**
     * Syncs emails for all accounts or a specific account.
     * For MVP, we might just call this manually or periodically.
     */
    @Transactional
    public void syncEmailsForAccount(Long accountId, String folderName, int limit, int page) {
        EmailAccount account = emailAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (!imapService.testConnection(account)) {
            log.error("Cannot connect to account: " + account.getEmailAddress());
            return;
        }

        // Fetch recent emails from specified folder (or INBOX)
        // In a real app, we would track the last synced UID per folder.
        try {
            List<MailMessageDto> messages = imapService.getMessages(account, folderName, page, limit);

            for (MailMessageDto msg : messages) {
                java.util.Optional<EmailEntity> existingOpt = emailRepository.findByMessageId(msg.getMessageId());
                log.debug("Processing email: {} | Body length: {}", msg.getSubject(), 
                    msg.getBody() != null ? msg.getBody().length() : 0);
                if (existingOpt.isPresent()) {
                    EmailEntity existing = existingOpt.get();
                    // If body is missing, update it
                    if (existing.getBody() == null || existing.getBody().isEmpty()) {
                        if (msg.getBody() != null && !msg.getBody().isEmpty()) {
                            existing.setBody(msg.getBody());
                            // Generate embedding for updated body
                            generateAndSetEmbedding(existing, msg.getSubject(), msg.getBody());
                            emailRepository.save(existing); // Save body update
                            log.info("Updated body and embedding for email ID: {}", existing.getId());
                        }
                    }

                    // Update read status if changed
                    if (existing.isRead() != msg.isRead()) {
                        existing.setRead(msg.isRead());
                        emailRepository.save(existing);
                    }

                    // Update attachment status if changed (fix for false positives)
                    if (existing.isHasAttachments() != msg.isHasAttachments()) {
                        existing.setHasAttachments(msg.isHasAttachments());
                        emailRepository.save(existing);
                    }
                    continue; 
                }

                EmailEntity entity = EmailEntity.builder()
                        .messageId(msg.getMessageId())
                        .uid(msg.getUid())
                        .subject(msg.getSubject())
                        .sender(msg.getFrom())
                        .snippet(msg.getPreview())
                        .body(msg.getBody()) // Now actually saving the body
                        .receivedDate(msg.getReceivedAt())
                        .isRead(msg.isRead())
                        .hasAttachments(msg.isHasAttachments())
                        // Note: We might want to track folder name in entity later, 
                        // but for now we just treat everything as INBOX scope or generic email.
                        // Setting status as INBOX for now for all synced emails so they appear on board.
                        .status(EmailStatus.INBOX)
                        .account(account)
                        .build();

                // Generate embedding for new email
                generateAndSetEmbedding(entity, msg.getSubject(), msg.getBody());

                emailRepository.save(entity);
            }
        } catch (jakarta.mail.MessagingException e) {
            log.error("Failed to fetch messages for account: " + account.getEmailAddress(), e);
        }
    }

    /**
     * Background task to wake up snoozed emails.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void checkSnoozedEmails() {
        LocalDateTime now = LocalDateTime.now();
        List<EmailEntity> snoozedEmails = emailRepository.findBySnoozedUntilBeforeAndStatus(now, EmailStatus.SNOOZED);

        for (EmailEntity email : snoozedEmails) {
            log.info("Waking up email ID: {}", email.getId());
            email.setStatus(EmailStatus.INBOX);
            email.setSnoozedUntil(null);
            emailRepository.save(email);
        }
    }

    private void generateAndSetEmbedding(EmailEntity entity, String subject, String body) {
        try {
            String textToEmbed = (subject != null ? subject : "") + " " + (body != null ? body : "");
            // Truncate to avoid token limits if necessary (basic check)
            if (textToEmbed.length() > 8000) {
                textToEmbed = textToEmbed.substring(0, 8000);
            }
            if (!textToEmbed.trim().isEmpty()) {
                List<Float> embedding = embeddingService.generateEmbedding(textToEmbed);
                entity.setEmbedding(embedding);
            }
        } catch (Exception e) {
            log.error("Failed to generate embedding for email: {}", entity.getMessageId(), e);
            // We continue without embedding, can retry later
        }
    }
}
