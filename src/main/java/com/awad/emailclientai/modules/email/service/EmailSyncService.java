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

    /**
     * Syncs emails for all accounts or a specific account.
     * For MVP, we might just call this manually or periodically.
     */
    @Transactional
    public void syncEmailsForAccount(Long accountId, String folderName, int limit) {
        EmailAccount account = emailAccountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if (!imapService.testConnection(account)) {
            log.error("Cannot connect to account: " + account.getEmailAddress());
            return;
        }

        // Fetch recent emails from specified folder (or INBOX)
        // In a real app, we would track the last synced UID per folder.
        try {
            List<MailMessageDto> messages = imapService.getMessages(account, folderName, 0, limit);

            for (MailMessageDto msg : messages) {
                if (emailRepository.findByMessageId(msg.getMessageId()).isPresent()) {
                    continue; // Skip if already exists
                }

                EmailEntity entity = EmailEntity.builder()
                        .messageId(msg.getMessageId())
                        .uid(msg.getUid())
                        .subject(msg.getSubject())
                        .sender(msg.getFrom())
                        .snippet(msg.getPreview())
                        .receivedDate(msg.getReceivedAt())
                        // Note: We might want to track folder name in entity later, 
                        // but for now we just treat everything as INBOX scope or generic email.
                        // Setting status as INBOX for now for all synced emails so they appear on board.
                        .status(EmailStatus.INBOX)
                        .account(account)
                        .build();

                emailRepository.save(entity);
            }
        } catch (jakarta.mail.MessagingException e) {
            log.error("Failed to fetch messages for account: " + account.getEmailAddress(), e);
        }
    }
}
