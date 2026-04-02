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

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;

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
    private final NotificationWebSocketHandler notificationWebSocketHandler;

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

    @Transactional
    public void repairEmailsForUser(Long userId) {
        List<EmailAccount> accounts = accountRepository.findByUserIdAndActiveTrue(userId);
        for (EmailAccount account : accounts) {
            repairCorruptedEmails(account.getId());
        }
    }

    @Transactional
    public void repairCorruptedEmails(Long accountId) {
        List<EmailEntity> corrupted = emailRepository.findCorruptedEmails(accountId);
        if (corrupted.isEmpty()) {
            log.info("No corrupted emails found for account: {}", accountId);
            return;
        }

        log.info("Found {} corrupted emails for account: {}. Starting repair...", corrupted.size(), accountId);
        EmailAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return;

        for (EmailEntity entity : corrupted) {
            try {
                // Fetch full detail with HTML
                var detail = imapService.getMessageDetail(account, "INBOX", entity.getUid());
                String newBody = (detail.getBodyHtml() != null && !detail.getBodyHtml().isEmpty()) 
                                 ? detail.getBodyHtml() 
                                 : detail.getBodyText();

                if (detail != null && newBody != null) {
                    entity.setBody(newBody);
                    // Re-generate preview and snippet
                    String plainText = imapService.stripHtml(newBody);
                    entity.setSnippet(plainText.length() > 150 ? plainText.substring(0, 147) + "..." : plainText);
                    
                    // Re-generate embedding
                    generateAndSetEmbedding(entity, entity.getSubject(), newBody);
                    
                    emailRepository.save(entity);
                    log.info("Successfully repaired email ID: {}", entity.getId());
                }
            } catch (Exception e) {
                log.error("Failed to repair email ID: {}. Error: {}", entity.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void refreshEmail(Long emailId) {
        EmailEntity entity = emailRepository.findById(emailId).orElseThrow(() -> new RuntimeException("Email not found"));
        EmailAccount account = entity.getAccount();
        
        try {
            var detail = imapService.getMessageDetail(account, "INBOX", entity.getUid());
            String newBody = (detail.getBodyHtml() != null && !detail.getBodyHtml().isEmpty()) 
                             ? detail.getBodyHtml() 
                             : detail.getBodyText();

            if (newBody != null) {
                entity.setBody(newBody);
                String plainText = imapService.stripHtml(newBody);
                entity.setSnippet(plainText.length() > 150 ? plainText.substring(0, 147) + "..." : plainText);
                generateAndSetEmbedding(entity, entity.getSubject(), newBody);
                emailRepository.save(entity);
                log.info("Successfully refreshed email ID: {}", emailId);
            }
        } catch (Exception e) {
            log.error("Failed to refresh email ID: {}. Error: {}", emailId, e.getMessage());
            throw new RuntimeException("Refresh failed: " + e.getMessage());
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

                Optional<EmailEntity> existingOpt = emailRepository.findByMessageId(msg.getMessageId());
                if (existingOpt.isPresent()) {
                    EmailEntity existing = existingOpt.get();
                    boolean changed = false;

                    // Update Gmail IDs if missing
                    if (existing.getGmailMessageId() == null && msg.getGmailMessageId() != null) {
                        existing.setGmailMessageId(msg.getGmailMessageId());
                        changed = true;
                    }
                    if (existing.getThreadId() == null && msg.getThreadId() != null) {
                        existing.setThreadId(msg.getThreadId());
                        changed = true;
                    }

                    // If body is missing or looks corrupted (e.g. starts with CSS after the stripping bug), update it
                    boolean isCorrupted = existing.getBody() != null && 
                                          (existing.getBody().contains("body {") || 
                                           existing.getBody().contains(".ie-browser") ||
                                           existing.getBody().contains(".mso-container") ||
                                           existing.getBody().contains("ExternalClass")); 
                    
                    if (existing.getBody() == null || existing.getBody().isEmpty() || isCorrupted) {
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
                        changed = true;
                    }

                    // Update star status if changed
                    if (existing.isStarred() != msg.isStarred()) {
                        existing.setStarred(msg.isStarred());
                        changed = true;
                    }

                    // Migration: If existing status is STARRED, move to targetStatus and set isStarred based on Gmail
                    if ("STARRED".equalsIgnoreCase(existing.getStatus())) {
                        existing.setStatus(targetStatus);
                        existing.setStarred(msg.isStarred());
                        changed = true;
                        log.info("Migrating legacy STARRED status to {} and setting isStarred={}", 
                            targetStatus, msg.isStarred());
                    } else if (!existing.getStatus().equals(targetStatus)) {
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
                        .threadId(msg.getThreadId())
                        .gmailMessageId(msg.getGmailMessageId())
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
                        .kanbanOrder((double) msg.getReceivedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                        .build();

                try {
                    emailRepository.save(entity);
                    // Generate embedding after entity is persisted (has ID)
                    generateAndSetEmbedding(entity, msg.getSubject(), msg.getBody());
                } catch (DataIntegrityViolationException e) {
                    log.warn("Duplicate email detected during sync (messageId: {}), skipping.", msg.getMessageId());
                }
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
        OffsetDateTime now = OffsetDateTime.now();
        List<EmailEntity> snoozedEmails = emailRepository.findBySnoozedUntilBeforeAndStatus(now, EmailStatus.SNOOZED);

        if (snoozedEmails.isEmpty()) {
            return;
        }

        java.util.Set<Long> affectedAccountIds = new java.util.HashSet<>();

        for (EmailEntity email : snoozedEmails) {
            log.info("Waking up email ID: {}", email.getId());
            email.setStatus(EmailStatus.INBOX);
            email.setSnoozedUntil(null);
            emailRepository.save(email);
            
            if (email.getAccount() != null) {
                affectedAccountIds.add(email.getAccount().getId());
            }
        }

        // Notify frontend for each affected account
        for (Long accountId : affectedAccountIds) {
            String payload = "{\"type\": \"NEW_EMAILS\", \"message\": \"Email(s) returned from snooze\"}";
            notificationWebSocketHandler.sendNotification(accountId, payload);
            log.info("Sent wake-up notification for account ID: {}", accountId);
        }
    }

    private void generateAndSetEmbedding(EmailEntity entity, String subject, String body) {
        try {
            if (entity.getId() == null) {
                log.warn("Cannot generate embedding for unsaved entity (messageId: {})", entity.getMessageId());
                return;
            }

            String cleanBody = imapService.stripHtml(body);
            String textToEmbed = (subject != null ? subject : "") + " " + (cleanBody != null ? cleanBody : "");
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
                    .collect(Collectors.joining(",")) + "]";

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
        return kanbanService.createColumn(accountId, labelName, labelName, "#f1f5f9");
    }
}
