package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.MailMessageDetailDto;
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
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
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

    @org.springframework.beans.factory.annotation.Value("${app.mail.sync.batch-size:20}")
    private int batchSize;

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

        log.info("[V13-REPAIR] Found {} corrupted emails for account: {}. Starting efficient repair...", corrupted.size(), accountId);
        EmailAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return;

        // Efficiency: Open ONE store and ONE folder for the whole Batch
        try (jakarta.mail.Store store = imapService.connectToStore(account)) {
            jakarta.mail.Folder folder = store.getFolder("INBOX");
            folder.open(jakarta.mail.Folder.READ_ONLY);

            for (EmailEntity entity : corrupted) {
                try {
                    // Fetch full detail using the shared folder
                    var detail = imapService.getMessageDetail(folder, entity.getUid());
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
                        log.info("[V13-REPAIR] Successfully repaired email ID: {}", entity.getId());
                    }
                } catch (Exception e) {
                    log.error("[V13-REPAIR] Failed to repair email ID: {}. Error: {}", entity.getId(), e.getMessage());
                }
            }
            folder.close(false);
        } catch (Exception e) {
            log.error("[V13-REPAIR] Fatal error during batch repair for account {}: {}", accountId, e.getMessage());
        }
    }


    @Transactional
    public void refreshEmail(Long emailId) {
        EmailEntity entity = emailRepository.findById(emailId).orElseThrow(() -> new RuntimeException("Email not found"));
        EmailAccount account = entity.getAccount();
        
        try {
            // Choose folder based on entity status and provider (Sent folder names differ per provider)
            String folderName = "INBOX";
            if (entity.getStatus() != null && entity.getStatus().equalsIgnoreCase("SENT")) {
                if (account.getProvider() == EmailProvider.GMAIL) folderName = "[Gmail]/Sent Mail";
                else folderName = "Sent";
            }
            var detail = imapService.getMessageDetail(account, folderName, entity.getUid());
            String newBody = (detail.getBodyHtml() != null && !detail.getBodyHtml().isEmpty()) 
                             ? detail.getBodyHtml() 
                             : detail.getBodyText();

            if (newBody != null) {
                entity.setBody(newBody);
                String plainText = imapService.stripHtml(newBody);
                entity.setSnippet(plainText.length() > 150 ? plainText.substring(0, 147) + "..." : plainText);
                
                // CRITICAL: Clear summary so it gets re-generated with new clean logic
                entity.setSummary(null);
                entity.setSummarySource(null);
                
                // CRITICAL: Clear and re-sync attachments to catch missing inline images (CIDs)
                if (detail.getAttachments() != null) {
                    if (detail.getAttachments().isEmpty() && entity.isHasAttachments()) {
                        log.warn("[V9-SYNC-WARNING] Email ID: {} hasAttachments=true but IMAP detail returned 0 attachments!", emailId);
                    }
                    entity.getAttachments().clear();
                    entity.getAttachments().addAll(mapDetailAttachments(detail.getAttachments(), entity));
                }
                
                generateAndSetEmbedding(entity, entity.getSubject(), newBody);
                emailRepository.save(entity);
                log.info("Successfully refreshed email ID: {} and cleared stale summary.", emailId);
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
            int currentLimit = limit > 0 ? limit : batchSize;
            int currentPage = page;
            int totalNewFound = 0;
            int maxPagesToTry = 3; // Prevent infinite loops
            
            log.info("[V11-SYNC] Starting sync for account: {} (Folder: {}, Initial Page: {})", 
                account.getEmailAddress(), folderName, currentPage);

            // Collect newly created email IDs so we can notify frontend with exact items
            List<Long> newEmailIds = new ArrayList<>();

            while (totalNewFound < currentLimit && (currentPage - page) < maxPagesToTry) {
                List<MailMessageDto> messages = imapService.getMessages(account, folderName, currentPage, currentLimit);
                if (messages.isEmpty()) {
                    log.info("[V11-SYNC] No more messages found on server at page {}", currentPage);
                    break;
                }

                for (MailMessageDto msg : messages) {
                    processMessage(msg, account, accountId, folderName, newEmailIds);
                    
                    // Count how many are new for "Smart Sync" quota
                    if (!emailRepository.existsByMessageId(msg.getMessageId())) {
                        totalNewFound++;
                    }

                }
                
                currentPage++;
            }
            // After finishing the sync batch, if we created new emails, notify frontend with their IDs
            if (!newEmailIds.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> payload = Map.of(
                            "type", "NEW_EMAILS",
                            "emailIds", newEmailIds
                    );
                    String payloadJson = mapper.writeValueAsString(payload);
                    notificationWebSocketHandler.sendNotification(account.getId(), "NEW_EMAILS", payloadJson);
                    log.info("[V11-SYNC] Sent NEW_EMAILS notification with {} ids for account {}", newEmailIds.size(), account.getId());
                } catch (Exception e) {
                    log.warn("[V11-SYNC] Failed to send detailed NEW_EMAILS notification: {}", e.getMessage());
                }
            }
        } catch (jakarta.mail.MessagingException e) {
            log.error("Failed to fetch messages for account: " + account.getEmailAddress(), e);
        }
    }

    private void processMessage(MailMessageDto msg, EmailAccount account, Long accountId, String folderName, List<Long> newEmailIds) {
        log.info("[SYNC-TRACE] Processing message: messageId={}, uid={}, gmailMessageId={}, hasAttachments={}, attachmentsCount={}, folder={}",
            msg.getMessageId(), msg.getUid(), msg.getGmailMessageId(), msg.isHasAttachments(), (msg.getAttachments() != null ? msg.getAttachments().size() : 0), folderName);

        // Proactive: if IMAP says there are attachments but the lightweight list metadata is empty,
        // do a targeted getMessageDetail to populate attachments and body BEFORE persisting.
        if (msg.isHasAttachments() && (msg.getAttachments() == null || msg.getAttachments().isEmpty())) {
            try {
                log.info("[SYNC-TRACE] Attachment metadata missing for messageId={}, attempting pre-save IMAP detail fetch (folder={})", msg.getMessageId(), folderName);
                MailMessageDetailDto detail = imapService.getMessageDetail(account, folderName, msg.getUid());
                if (detail != null) {
                    // Populate body if missing
                    String newBody = detail.getBodyHtml() != null && !detail.getBodyHtml().isEmpty() ? detail.getBodyHtml() : detail.getBodyText();
                    if ((msg.getBody() == null || msg.getBody().isEmpty()) && newBody != null) {
                        msg.setBody(newBody);
                    }

                    // Convert detail attachments to list-view metadata
                    if (detail.getAttachments() != null && !detail.getAttachments().isEmpty()) {
                        List<MailMessageDto.AttachmentMetadataDto> meta = new ArrayList<>();
                        int[] idx = new int[]{0};
                        for (var at : detail.getAttachments()) {
                            MailMessageDto.AttachmentMetadataDto m = MailMessageDto.AttachmentMetadataDto.builder()
                                    .id(at.getId())
                                    .filename(at.getFilename())
                                    .contentType(at.getContentType())
                                    .size(at.getSize())
                                    .contentId(at.getContentId())
                                    .inline(at.isInline())
                                    .externalUrl(at.getExternalUrl())
                                    .build();
                            meta.add(m);
                            idx[0]++;
                        }
                        msg.setAttachments(meta);
                        log.info("[SYNC-TRACE] Populated {} attachment metadata entries from IMAP for messageId={}", meta.size(), msg.getMessageId());
                    }
                }
            } catch (Exception e) {
                log.warn("[SYNC-TRACE] Pre-save IMAP detail fetch failed for messageId={}: {}", msg.getMessageId(), e.getMessage());
            }
        }
        String targetStatus = determineStatusFromLabels(msg, accountId);
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

            // If body is missing or looks corrupted (starts with CSS after the stripping bug), update it
            boolean isCorrupted = existing.getBody() != null && 
                                  (existing.getBody().contains("body {") || 
                                   existing.getBody().contains(".ie-browser") ||
                                   existing.getBody().contains(".mso-container") ||
                                   existing.getBody().contains("ExternalClass") ||
                                   existing.getBody().contains("class=\"mb-plain-text-body\"")); 
            
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
                // Skip embeddings for unimportant folders
                String status = existing.getStatus();
                if (status != null && !status.equalsIgnoreCase("TRASH") && !status.equalsIgnoreCase("SPAM")) {
                    generateAndSetEmbedding(existing, existing.getSubject(), existing.getSnippet());
                    if (existing.getEmbedding768() != null || existing.getEmbedding384() != null) {
                        changed = true;
                    }
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

            if (existing.isHasAttachments() != msg.isHasAttachments()) {
                existing.setHasAttachments(msg.isHasAttachments());
                changed = true;
            }

            // Sync missing names/recipients for existing emails (Migration Bridge)
            if (existing.getFromName() == null && msg.getFromName() != null) {
                existing.setFromName(msg.getFromName());
                changed = true;
            }
            if (existing.getRecipientTo() == null && msg.getTo() != null) {
                existing.setRecipientTo(String.join(", ", msg.getTo()));
                changed = true;
            }
            if (existing.getRecipientCc() == null && msg.getCc() != null) {
                existing.setRecipientCc(String.join(", ", msg.getCc()));
                changed = true;
            }

            // Update attachments if missing or changed (V10.32: Smarter merging for cloud links)
            if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                boolean attachmentListChanged = false;
                for (var msgAt : msg.getAttachments()) {
                    // Check if this specific attachment already exists (by server ID or external URL)
                    boolean alreadyExists = existing.getAttachments().stream().anyMatch(dbAt -> {
                        if (msgAt.getExternalUrl() != null) {
                            return msgAt.getExternalUrl().equals(dbAt.getExternalUrl());
                        }
                        return msgAt.getId() != null && msgAt.getId().equals(dbAt.getServerAttachmentId());
                    });

                    if (!alreadyExists) {
                        existing.getAttachments().add(com.awad.emailclientai.modules.email.entity.EmailAttachment.builder()
                                .email(existing)
                                .filename(msgAt.getFilename())
                                .contentType(msgAt.getContentType())
                                .size(msgAt.getSize())
                                .serverAttachmentId(msgAt.getId())
                                .contentId(msgAt.getContentId())
                                .inline(msgAt.isInline())
                                .externalUrl(msgAt.getExternalUrl())
                                .build());
                        attachmentListChanged = true;
                    }
                }
                if (attachmentListChanged) {
                    changed = true;
                }
            }

            if (changed) {
                emailRepository.save(existing);
            }
            return;
        }

        // New Email Creation
        EmailEntity entity = EmailEntity.builder()
                .messageId(msg.getMessageId())
                .threadId(msg.getThreadId())
                .gmailMessageId(msg.getGmailMessageId())
                .uid(msg.getUid())
                .subject(msg.getSubject())
                .sender(msg.getFrom())
                .fromName(msg.getFromName())
                .recipientTo(msg.getTo() != null ? String.join(", ", msg.getTo()) : null)
                .recipientCc(msg.getCc() != null ? String.join(", ", msg.getCc()) : null)
                .snippet(msg.getPreview())
                .body(msg.getBody())
                .receivedDate(msg.getReceivedAt())
                .isRead(msg.isRead())
                .hasAttachments(msg.isHasAttachments())
                .status(targetStatus)
                .account(account)
                .kanbanOrder((double) msg.getReceivedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();

        // Map and set attachments
        if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
            entity.getAttachments().clear();
            entity.getAttachments().addAll(mapAttachments(msg.getAttachments(), entity));
        }

        try {
            emailRepository.save(entity);
            // Record newly created email ID for detailed WS notification
            try {
                if (newEmailIds != null) newEmailIds.add(entity.getId());
            } catch (Exception ignored) {}
            // Generate embedding after entity is persisted (has ID)
            // Skip for TRASH and SPAM
            if (!"TRASH".equalsIgnoreCase(targetStatus) && !"SPAM".equalsIgnoreCase(targetStatus)) {
                generateAndSetEmbedding(entity, msg.getSubject(), msg.getPreview());
            }
            // If IMAP reported attachments but msg didn't include attachment metadata
            // or entity indicates attachments but none were mapped, attempt a detail refresh
            boolean msgHasAttachmentMeta = msg.getAttachments() != null && !msg.getAttachments().isEmpty();
            if (!msgHasAttachmentMeta && (msg.isHasAttachments() || entity.isHasAttachments())) {
                try {
                    log.info("Post-create detail refresh for email ID {} to populate attachments.", entity.getId());
                    refreshEmail(entity.getId());
                } catch (Exception e) {
                    log.warn("Failed to refresh details for email ID {}: {}", entity.getId(), e.getMessage());
                }
            }
        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate email detected during sync (messageId: {}), skipping.", msg.getMessageId());
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
            notificationWebSocketHandler.sendNotification(accountId, "NEW_EMAILS", "Email(s) returned from snooze");
            log.info("Sent wake-up notification for account ID: {}", accountId);
        }
    }

    private void generateAndSetEmbedding(EmailEntity entity, String subject, String body) {
        try {
            if (entity.getId() == null) {
                log.warn("Cannot generate embedding for unsaved entity (messageId: {})", entity.getMessageId());
                return;
            }

            // We now embed Subject + Snippet (Pre-cleaned 150 chars) instead of full body
            // This drastically reduces API quota usage and is sufficient for semantic search.
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

    private List<com.awad.emailclientai.modules.email.entity.EmailAttachment> mapAttachments(
            List<MailMessageDto.AttachmentMetadataDto> dtos, EmailEntity email) {
        return dtos.stream().map(dto -> com.awad.emailclientai.modules.email.entity.EmailAttachment.builder()
                .email(email)
                .filename(dto.getFilename())
                .contentType(dto.getContentType())
                .size(dto.getSize())
                .serverAttachmentId(dto.getId())
                .contentId(dto.getContentId())
                .inline(dto.isInline())
                .externalUrl(dto.getExternalUrl())
                .build())
                .collect(Collectors.toList());
    }

    private List<com.awad.emailclientai.modules.email.entity.EmailAttachment> mapDetailAttachments(
            List<MailMessageDetailDto.AttachmentDto> dtos, EmailEntity email) {
        log.info("[V8-ATTACH-SYNC] Mapping {} attachments for email ID: {}", dtos.size(), email.getId());
        dtos.forEach(dto -> log.info("  - CID: {}, File: {}, Inline: {}", dto.getContentId(), dto.getFilename(), dto.isInline()));
        
        return dtos.stream().map(dto -> com.awad.emailclientai.modules.email.entity.EmailAttachment.builder()
                .email(email)
                .filename(dto.getFilename())
                .contentType(dto.getContentType())
                .size(dto.getSize())
                .serverAttachmentId(dto.getId())
                .contentId(dto.getContentId())
                .inline(dto.isInline())
                .externalUrl(dto.getExternalUrl())
                .build())
                .collect(Collectors.toList());
    }
}
