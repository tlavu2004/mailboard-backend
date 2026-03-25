package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.MailMessageDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.repository.KanbanColumnRepository;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSyncService {

    private final ImapService imapService;
    private final EmailRepository emailRepository;
    private final EmailAccountRepository accountRepository;
    private final EmbeddingService embeddingService;
    private final KanbanColumnRepository kanbanColumnRepository;
    private final KanbanService kanbanService;

    /** Gmail system labels to ignore when determining custom labels */
    private static final Set<String> SYSTEM_LABELS = Set.of(
            "INBOX", "SENT", "DRAFT", "DRAFTS", "SPAM", "TRASH",
            "STARRED", "IMPORTANT", "UNREAD",
            "CATEGORY_PERSONAL", "CATEGORY_SOCIAL",
            "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES", "CATEGORY_FORUMS"
    );

    @Transactional
    public void syncEmailsForAccount(Long accountId, String folderName, int limit, int page) {
        EmailAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));

        syncAccount(account, folderName, limit, page);
    }

    @Transactional
    public void syncEmailsForAccount(Long accountId, Long userId, String folderName, int limit, int page) {
        EmailAccount account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));

        syncAccount(account, folderName, limit, page);
    }

    @Transactional
    public void syncEmailsForUser(Long userId, String folderName, int limit, int page) {
        List<EmailAccount> accounts = accountRepository.findByUserIdAndActiveTrue(userId);
        if (accounts.isEmpty()) {
            log.info("No active email accounts found for user ID: {}", userId);
            return;
        }

        for (EmailAccount account : accounts) {
            try {
                syncAccount(account, folderName, limit, page);
            } catch (Exception e) {
                log.error("Failed to sync account: {} for user: {}", account.getEmailAddress(), userId, e);
            }
        }
    }

    private void syncAccount(EmailAccount account, String folderName, int limit, int page) {
        if (!imapService.testConnection(account)) {
            log.info("Cannot connect to account: " + account.getEmailAddress());
            return;
        }

        Long accountId = account.getId();

        try {
            List<MailMessageDto> messages = imapService.getMessages(account, folderName, page, limit);

            for (MailMessageDto msg : messages) {
                String targetStatus = determineStatusFromLabels(msg, accountId);
                log.info("Syncing email: {} | Labels: {} | Target Status: {}",
                    msg.getSubject(), msg.getLabels(), targetStatus);

                java.util.Optional<EmailEntity> existingOpt = emailRepository.findByMessageId(msg.getMessageId());
                if (existingOpt.isPresent()) {
                    EmailEntity existing = existingOpt.get();
                    boolean changed = false;

                    // If body is missing, update it
                    if (existing.getBody() == null || existing.getBody().isEmpty()) {
                        if (msg.getBody() != null && !msg.getBody().isEmpty()) {
                            existing.setBody(msg.getBody());
                            changed = true;
                        }
                    }

                    // If embedding of the currently preferred dimension is missing, generate it
                    int preferredDim = embeddingService.getPreferredDimension();
                    boolean hasPreferred = (preferredDim == 768 && existing.getEmbedding768() != null) ||
                                         (preferredDim == 384 && existing.getEmbedding384() != null);

                    if (!hasPreferred && existing.getBody() != null && !existing.getBody().isEmpty()) {
                        generateAndSetEmbedding(existing, existing.getSubject(), existing.getBody());
                        if (existing.getEmbedding768() != null || existing.getEmbedding384() != null) {
                            changed = true;
                        }
                    }

                    if (changed) {
                        emailRepository.save(existing);
                        log.info("Updated email ID: {} (Body/Embedding)", existing.getId());
                    }

                    // Update read status if changed
                    if (existing.isRead() != msg.isRead()) {
                        existing.setRead(msg.isRead());
                        emailRepository.save(existing);
                    }

                    // Update status if label mapping changed it
                    if (!existing.getStatus().equals(targetStatus)) {
                        log.info("Updating status for email ID {} from {} to {} based on labels",
                            existing.getId(), existing.getStatus(), targetStatus);
                        existing.setStatus(targetStatus);
                        changed = true;
                    }

                    // Update hasAttachments if changed
                    if (existing.isHasAttachments() != msg.isHasAttachments()) {
                        existing.setHasAttachments(msg.isHasAttachments());
                        changed = true;
                    }

                    if (changed) {
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
                        .body(msg.getBody())
                        .receivedDate(msg.getReceivedAt())
                        .isRead(msg.isRead())
                        .hasAttachments(msg.isHasAttachments())
                        .status(targetStatus)
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
            if (textToEmbed.length() > 8000) {
                textToEmbed = textToEmbed.substring(0, 8000);
            }
            if (textToEmbed.trim().isEmpty()) {
                return;
            }

            List<Float> embeddingList = embeddingService.generateEmbedding(textToEmbed);
            if (embeddingList == null || embeddingList.isEmpty()) {
                return;
            }

            String embeddingString = "[" + embeddingList.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")) + "]";

            if (entity.getId() == null) {
                emailRepository.save(entity);
            }

            int dimension = embeddingList.size();
            if (dimension == 768) {
                emailRepository.updateEmbedding768(entity.getId(), embeddingString);
            } else if (dimension == 384) {
                emailRepository.updateEmbedding384(entity.getId(), embeddingString);
            } else {
                log.warn("Unexpected embedding dimension: {}. Skipping.", dimension);
            }
        } catch (Exception e) {
            log.error("Failed to generate embedding for email: {}", entity.getMessageId(), e);
        }
    }

    /**
     * Determines the Kanban status for an email based on its Gmail labels.
     * Rules:
     *   - Filter out system labels (INBOX, SENT, SPAM, etc.)
     *   - If exactly 1 custom label remains -> find or create a Kanban column for it
     *   - If 0 or >1 custom labels -> default to INBOX
     */
    private String determineStatusFromLabels(MailMessageDto msg, Long accountId) {
        if (msg.getLabels() == null || msg.getLabels().isEmpty()) {
            return EmailStatus.INBOX;
        }

        // Filter out system labels (case-insensitive)
        List<String> customLabels = msg.getLabels().stream()
                .filter(label -> !SYSTEM_LABELS.contains(label.toUpperCase()))
                .collect(Collectors.toList());

        if (customLabels.size() != 1) {
            if (customLabels.size() > 1) {
                log.info("Email '{}' has {} custom labels {} -> keeping in INBOX",
                        msg.getSubject(), customLabels.size(), customLabels);
            }
            return EmailStatus.INBOX;
        }

        // Exactly 1 custom label -> find or create column
        String labelName = customLabels.get(0);
        KanbanColumn column = findOrCreateColumn(accountId, labelName);
        log.info("Auto-mapping email '{}' to column '{}' (status: {}) based on label '{}'",
                msg.getSubject(), column.getName(), column.getLinkedStatus(), labelName);
        return column.getLinkedStatus();
    }

    /**
     * Finds an existing Kanban column matching the given label (case-insensitive),
     * or creates a new one if none exists.
     */
    private KanbanColumn findOrCreateColumn(Long accountId, String labelName) {
        // Try to find existing column by gmailLabelId (case-insensitive)
        Optional<KanbanColumn> existing = kanbanColumnRepository
                .findByAccountIdAndGmailLabelIdIgnoreCase(accountId, labelName);

        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new column
        log.info("Creating new Kanban column for label: '{}'", labelName);
        return kanbanService.createColumn(accountId, labelName, labelName);
    }
}
