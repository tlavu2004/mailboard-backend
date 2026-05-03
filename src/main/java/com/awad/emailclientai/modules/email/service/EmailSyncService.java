package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.request.SendEmailRequestDto;
import com.awad.emailclientai.modules.email.dto.response.MailMessageDetailDto;
import com.awad.emailclientai.modules.email.dto.response.MailMessageDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailAttachment;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.modules.email.entity.EmailSender;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.repository.EmailSenderRepository;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.repository.KanbanColumnRepository;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Folder;
import jakarta.mail.Store;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;




@Service
@Slf4j
public class EmailSyncService {

    private final ImapService imapService;
    private final EmailRepository emailRepository;
    private final EmailAccountRepository accountRepository;
    private final EmbeddingService embeddingService;
    private final KanbanColumnRepository kanbanColumnRepository;
    private final KanbanService kanbanService;
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final GmailLabelService gmailLabelService;
    private final TransactionTemplate transactionTemplate;
    private final EmailSenderRepository emailSenderRepository;
    private final Set<String> activeSyncs = ConcurrentHashMap.newKeySet();

    public EmailSyncService(
            EmailRepository emailRepository,
            EmailAccountRepository accountRepository,
            ImapService imapService,
            EmbeddingService embeddingService,
            KanbanColumnRepository kanbanColumnRepository,
            KanbanService kanbanService,
            NotificationWebSocketHandler notificationWebSocketHandler,
            GmailLabelService gmailLabelService,
            PlatformTransactionManager transactionManager,
            EmailSenderRepository emailSenderRepository) {
        this.emailRepository = emailRepository;
        this.accountRepository = accountRepository;
        this.imapService = imapService;
        this.embeddingService = embeddingService;
        this.kanbanColumnRepository = kanbanColumnRepository;
        this.kanbanService = kanbanService;
        this.notificationWebSocketHandler = notificationWebSocketHandler;
        this.gmailLabelService = gmailLabelService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.emailSenderRepository = emailSenderRepository;
    }

    @Value("${app.mail.sync.batch-size:20}")
    private int batchSize;

    /** Gmail system labels to ignore when determining custom labels */
    private static final Set<String> SYSTEM_LABELS = Set.of(
            "INBOX", "SENT", "DRAFT", "DRAFTS", "SPAM", "TRASH",
            "STARRED", "IMPORTANT", "UNREAD", "CHAT",
            "\\\\TRASH", "\\\\SPAM", "\\\\DRAFT", "\\\\SENT", "\\\\INBOX",
            "CATEGORY_PERSONAL", "CATEGORY_SOCIAL",
            "CATEGORY_PROMOTIONS", "CATEGORY_UPDATES", "CATEGORY_FORUMS"
    );

    @Async
    public void syncEmailsForAccount(Long accountId, String folderName, int limit, int page) {
        EmailAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));

        syncAccount(account, folderName, limit, page);
    }

    @Async
    public void syncEmailsForAccount(Long accountId, Long userId, String folderName, int limit, int page) {
        EmailAccount account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));

        syncAccount(account, folderName, limit, page);
    }

    /**
     * Gmail History-based sync (V13.8)
     * Responds to Google Push notifications by fetching exactly what changed
     * since the last known history ID.
     */
    public void syncEmailsByHistory(EmailAccount account, Long newHistoryId) {
        if (account.getProvider() != EmailProvider.GMAIL || account.getWatchHistoryId() == null) {
            // Fallback to full system folder sync if no history tracking yet
            syncAccount(account, "INBOX", batchSize, 0);
            return;
        }

        Long startHistoryId = account.getWatchHistoryId();
        log.info("[GmailHistory] Syncing changes for {} from {} to {}", 
            account.getEmailAddress(), startHistoryId, newHistoryId);

        var historyList = gmailLabelService.getHistory(account, startHistoryId);
        if (historyList == null || historyList.isEmpty()) {
            log.info("[GmailHistory] No history records found since {}", startHistoryId);
            // Even if history is empty, update the ID to avoid re-syncing old history later
            transactionTemplate.execute(status -> {
                account.setWatchHistoryId(newHistoryId);
                accountRepository.save(account);
                return null;
            });
            return;
        }

        Set<String> affectedGmailMsgIds = new HashSet<>();
        for (var history : historyList) {
            if (history.getMessages() != null) {
                for (var msg : history.getMessages()) {
                    affectedGmailMsgIds.add(msg.getId());
                }
            }
            if (history.getMessagesAdded() != null) {
                for (var added : history.getMessagesAdded()) {
                    if (added.getMessage() != null) affectedGmailMsgIds.add(added.getMessage().getId());
                }
            }
        }

        log.info("[GmailHistory] Found {} unique Gmail message IDs affected", affectedGmailMsgIds.size());
        
        List<Long> updatedDbIds = new ArrayList<>();
        List<Long> newDbIds = new ArrayList<>();

        // Efficient Refresh: For each affected message, refresh its state from Gmail API
        for (String gmailId : affectedGmailMsgIds) {
            try {
                var gmailMsg = gmailLabelService.getMessage(account, gmailId);
                
                transactionTemplate.execute(txStatus -> {
                    if (gmailMsg == null) {
                        // Message deleted from Gmail, delete locally too
                        emailRepository.findByGmailMessageId(gmailId).ifPresent(entity -> {
                            emailRepository.delete(entity);
                            log.info("[GmailHistory] Deleted local record for {} as it is missing on Gmail", gmailId);
                        });
                        return null;
                    }

                    // Find local email by gmailMessageId
                    var existingOpt = emailRepository.findByGmailMessageId(gmailId);
                    if (existingOpt.isPresent()) {
                        EmailEntity existing = existingOpt.get();
                        
                        // Update labels/status
                        String newStatus = determineStatusFromGmailLabels(gmailMsg.getLabelIds(), account.getId());
                        boolean changed = false;
                        
                        if (!newStatus.equals(existing.getStatus())) {
                            log.info("[GmailHistory] Updating status for {} from {} to {} based on Gmail history",
                                existing.getId(), existing.getStatus(), newStatus);
                            existing.setStatus(newStatus);
                            changed = true;
                        }
                        
                        // Also sync Read status from labels
                        boolean isRead = !gmailMsg.getLabelIds().contains("UNREAD");
                        if (isRead != existing.isRead()) {
                            existing.setRead(isRead);
                            changed = true;
                        }
                        
                        if (changed) {
                            emailRepository.save(existing);
                            updatedDbIds.add(existing.getId());
                        }
                    } else {
                        // It's a truly new email that's not in our DB yet.
                        // Sync INBOX page 0 to discover it.
                        syncAccount(account, "INBOX", 1, 0);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("[GmailHistory] Failed to process change for Gmail ID {}: {}", gmailId, e.getMessage());
            }
        }

        // Save progress
        transactionTemplate.execute(txStatus -> {
            account.setWatchHistoryId(newHistoryId);
            accountRepository.save(account);
            return null;
        });

        // Notify UI AFTER transaction commit to ensure frontend sees latest DB state
        // Notify UI directly (since we are not in a long-running transaction anymore)
        if (!newDbIds.isEmpty() || !updatedDbIds.isEmpty()) {
            notifyBulk(account.getId(), newDbIds, updatedDbIds);
        }
    }

    private void notifyBulk(Long accountId, List<Long> newIds, List<Long> updatedIds) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            if (!newIds.isEmpty()) {
                notificationWebSocketHandler.sendRawNotification(accountId, 
                    mapper.writeValueAsString(Map.of("type", "NEW_EMAILS", "emailIds", newIds)));
            }
            if (!updatedIds.isEmpty()) {
                notificationWebSocketHandler.sendRawNotification(accountId, 
                    mapper.writeValueAsString(Map.of("type", "UPDATED_EMAILS", "emailIds", updatedIds)));
            }
        } catch (Exception e) {
            log.warn("Failed to send bulk sync notifications: {}", e.getMessage());
        }
    }

    @Async
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
        try (Store store = imapService.connectToStore(account)) {
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);

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
                        entity.setSnippet(plainText.length() > 200 ? plainText.substring(0, 197) + "..." : plainText);
                        
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


    public void refreshEmail(Long emailId) {
        EmailEntity entity = transactionTemplate.execute(status -> emailRepository.findById(emailId).orElseThrow(() -> new RuntimeException("Email not found")));
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
                final String finalBody = newBody;
                transactionTemplate.execute(txStatus -> {
                    // Re-fetch within transaction to avoid detached entity issues
                    EmailEntity activeEntity = emailRepository.findById(emailId).orElseThrow();
                    
                    activeEntity.setBody(finalBody);
                    String plainText = imapService.stripHtml(activeEntity.getBody());
                    activeEntity.setSnippet(plainText.length() > 200 ? plainText.substring(0, 197) + "..." : plainText);
                    
                    // CRITICAL: Clear summary so it gets re-generated with new clean logic
                    activeEntity.setSummary(null);
                    activeEntity.setSummarySource(null);
                    
                    // CRITICAL: Clear and re-sync attachments to catch missing inline images (CIDs)
                    if (detail.getAttachments() != null) {
                        activeEntity.getAttachments().clear();
                        activeEntity.getAttachments().addAll(mapDetailAttachments(detail.getAttachments(), activeEntity));
                    }
                    
                    emailRepository.save(activeEntity);
                    return null;
                });

                // Run embedding OUTSIDE transaction
                generateAndSetEmbedding(entity, entity.getSubject(), newBody);
                log.info("Successfully refreshed email ID: {} and cleared stale summary.", emailId);
            }
        } catch (Exception e) {
            log.error("Failed to refresh email ID: {}. Error: {}", emailId, e.getMessage());
            throw new RuntimeException("Refresh failed: " + e.getMessage());
        }
    }

    /**
     * Proactively saves an outgoing email (Sent or Draft) to the local database.
     * This avoids having to wait for IMAP synchronization to show the email in the UI.
     */
    @Transactional
    public EmailEntity saveLocalOutgoingEmail(EmailAccount account, SendEmailRequestDto request, String messageId, String status, String gmailMessageId) {
        log.info("[PROACTIVE-SAVE] Saving outgoing email (status={}): messageId={}, gmailMessageId={}", status, messageId, gmailMessageId);
        
        String cleanMessageId = messageId != null ? messageId.replaceAll("[<>]", "").trim() : null;
        
        EmailEntity entity = null;

        // 1. Primary check: gmailMessageId (Most reliable for Gmail)
        if (gmailMessageId != null && !gmailMessageId.isBlank()) {
            entity = emailRepository.findByGmailMessageId(gmailMessageId).orElse(null);
        }
        
        // 2. Secondary check: gmailDraftId
        if (entity == null && request.getGmailDraftId() != null && !request.getGmailDraftId().isEmpty()) {
            entity = emailRepository.findByGmailDraftId(request.getGmailDraftId()).orElse(null);
        }

        // 3. Tertiary check: messageId
        if (entity == null && cleanMessageId != null) {
            entity = emailRepository.findByMessageId(cleanMessageId).orElse(null);
        }

        if (entity == null) {
            entity = EmailEntity.builder()
                    .messageId(cleanMessageId != null ? cleanMessageId : "TEMP-" + System.currentTimeMillis())
                    .account(account)
                    .build();
        } else if (entity.getAccount() == null) {
            entity.setAccount(account);
        }
        
        if (gmailMessageId != null) {
            entity.setGmailMessageId(gmailMessageId);
        }

        entity.setAccount(account);
        entity.setSubject(request.getSubject());
        entity.setSender(account.getEmailAddress());
        entity.setFromName(account.getDisplayName());
        entity.setRecipientTo(request.getTo() != null ? String.join(", ", request.getTo()) : "");
        entity.setRecipientCc(request.getCc() != null ? String.join(", ", request.getCc()) : "");
        
        String body = request.getBodyHtml() != null && !request.getBodyHtml().isEmpty() ? request.getBodyHtml() : request.getBodyText();
        entity.setBody(body);
        
        String cleanSnippet = imapService.stripHtml(body);
        entity.setSnippet(cleanSnippet.length() > 200 ? cleanSnippet.substring(0, 197) + "..." : cleanSnippet);
        
        // V44: Learn recipients as contacts
        if (request.getTo() != null) request.getTo().forEach(t -> learnSenderName(t, null));
        if (request.getCc() != null) request.getCc().forEach(c -> learnSenderName(c, null));
        if (request.getBcc() != null) request.getBcc().forEach(b -> learnSenderName(b, null));

        entity.setStatus(status.toUpperCase());
        entity.setReceivedDate(LocalDateTime.now());
        entity.setRead(true); // Sent/Drafts are generally considered read
        
        EmailEntity saved = emailRepository.save(entity);
        log.info("[PROACTIVE-SAVE] Successfully saved email ID: {} with status: {}", saved.getId(), status);
        
        return saved;
    }

    private void syncAccount(EmailAccount account, String folderName, int limit, int page) {
        String syncKey = account.getId() + ":" + folderName;
        if (!activeSyncs.add(syncKey)) {
            log.info("[V11-SYNC] Sync already in progress for key: {}. Skipping.", syncKey);
            return;
        }

        try {
            if (!imapService.testConnection(account)) {
                log.info("Cannot connect to account: " + account.getEmailAddress());
                return;
            }

            Long accountId = account.getId();

            int currentLimit = limit > 0 ? limit : batchSize;
            int currentPage = page;
            int totalNewFound = 0;
            int maxPagesToTry = 3; // Prevent infinite loops
            
            log.info("[V11-SYNC] Starting sync for account: {} (Folder: {}, Initial Page: {})", 
                account.getEmailAddress(), folderName, currentPage);

            // Collect newly created or updated email IDs to notify frontend
            List<Long> newEmailIds = new ArrayList<>();
            List<Long> updatedEmailIds = new ArrayList<>();

            // Collect message IDs seen on server for reconciliation (especially for DRAFTS)
            Set<String> serverMessageIds = new HashSet<>();

            while (totalNewFound < currentLimit && (currentPage - page) < maxPagesToTry) {
                List<MailMessageDto> messages = imapService.getMessages(account, folderName, currentPage, currentLimit);
                if (messages.isEmpty()) {
                    log.info("[V11-SYNC] No more messages found on server at page {}", currentPage);
                    break;
                }

                for (MailMessageDto msg : messages) {
                    serverMessageIds.add(msg.getMessageId());
                    processMessage(msg, account, accountId, folderName, newEmailIds, updatedEmailIds);
                    
                    // Count how many are new for "Smart Sync" quota
                    if (!emailRepository.existsByMessageId(msg.getMessageId())) {
                        totalNewFound++;
                    }
                }
                currentPage++;
            }

            // RECONCILIATION: For DRAFTS folder, delete local records not seen on server
            if (folderName.toLowerCase().contains("draft") && !serverMessageIds.isEmpty()) {
                log.info("[RECONCILE] Checking for orphaned local drafts in account {}", accountId);
                List<EmailEntity> localDrafts = emailRepository.findAllByAccountIdAndStatus(accountId, "DRAFTS");
                for (EmailEntity localDraft : localDrafts) {
                    // Only delete if it's older than 5 minutes to avoid race conditions with proactive saves
                    if (localDraft.getReceivedDate() != null && 
                        localDraft.getReceivedDate().isBefore(LocalDateTime.now().minusMinutes(5))) {
                        
                        if (!serverMessageIds.contains(localDraft.getMessageId())) {
                            log.info("[RECONCILE] Deleting orphaned local draft ID: {} (MessageId: {})", localDraft.getId(), localDraft.getMessageId());
                            emailRepository.delete(localDraft);
                        }
                    }
                }
            }
            // After finishing the sync batch, notify frontend
            try {
                ObjectMapper mapper = new ObjectMapper();
                if (!newEmailIds.isEmpty()) {
                    Map<String, Object> payloadNew = Map.of(
                            "type", "NEW_EMAILS",
                            "emailIds", newEmailIds
                    );
                    notificationWebSocketHandler.sendRawNotification(account.getId(), mapper.writeValueAsString(payloadNew));
                    log.info("[V11-SYNC] Sent NEW_EMAILS notification with {} ids for account {}", newEmailIds.size(), account.getId());
                }
                
                if (!updatedEmailIds.isEmpty()) {
                    Map<String, Object> payloadUpdated = Map.of(
                            "type", "UPDATED_EMAILS",
                            "emailIds", updatedEmailIds
                    );
                    notificationWebSocketHandler.sendRawNotification(account.getId(), mapper.writeValueAsString(payloadUpdated));
                    log.info("[V11-SYNC] Sent UPDATED_EMAILS notification with {} ids for account {}", updatedEmailIds.size(), account.getId());
                }
            } catch (Exception e) {
                log.warn("[V11-SYNC] Failed to send WS notification: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to fetch messages for account: " + account.getEmailAddress(), e);
        } finally {
            activeSyncs.remove(syncKey);
            log.info("[V11-SYNC] Sync finished for key: {}", syncKey);
        }
    }

    private void processMessage(MailMessageDto msg, EmailAccount account, Long accountId, String folderName, List<Long> newEmailIds, List<Long> updatedEmailIds) {
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
        // Determine target status (Folder priority for system labels, Gmail labels for Kanban)
        String targetStatus = determineStatusFromLabels(msg, accountId, folderName);
        String messageId = msg.getMessageId();
        String gmailMessageId = msg.getGmailMessageId();
        
        // MOVED synchronized and transaction block to AFTER heavy IMAP calls
        synchronized (accountId.toString().intern()) {
            transactionTemplate.execute(txStatus -> {
                Optional<EmailEntity> existingOpt = Optional.empty();
        if (messageId != null && !messageId.isBlank()) {
            existingOpt = emailRepository.findByMessageId(messageId);
        }
        
        // Fallback: search by gmailMessageId if not found by RFC822 Message-ID
        if (existingOpt.isEmpty() && gmailMessageId != null && !gmailMessageId.isBlank()) {
            existingOpt = emailRepository.findByGmailMessageId(gmailMessageId);
            if (existingOpt.isPresent()) {
                log.info("[SYNC-MATCH] Found existing email by gmailMessageId fallback: {}", gmailMessageId);
            }
        }
        
        // Final fallback for DRAFTS: match by threadId AND subject if status is DRAFTS
        if (existingOpt.isEmpty() && "DRAFTS".equalsIgnoreCase(targetStatus)) {
            if (msg.getThreadId() != null) {
                existingOpt = emailRepository.findFirstByAccountIdAndThreadIdAndStatusOrderByReceivedDateDesc(accountId, msg.getThreadId(), "DRAFTS");
            }
            if (existingOpt.isEmpty() && msg.getSubject() != null && !msg.getSubject().isBlank()) {
                // Fuzzy match for draft by subject in the last 10 minutes
                LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
                existingOpt = emailRepository.findRecentEmailBySubject(accountId, msg.getSubject(), threshold);
            }
            if (existingOpt.isPresent()) {
                log.info("[SYNC-MATCH] Found existing draft fallback for merging (ID: {}, Status: {})", 
                    existingOpt.get().getId(), existingOpt.get().getStatus());
            }
        }
        
        // Fuzzy match for recently SENT emails (Because Gmail completely changes both Message-ID and GmailMessageId when sending)
        if (existingOpt.isEmpty() && ("SENT".equalsIgnoreCase(targetStatus) || "INBOX".equalsIgnoreCase(targetStatus))) {
            if (msg.getSubject() != null && !msg.getSubject().isBlank()) {
                // Look for an email with matching subject in the last 10 minutes
                LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
                existingOpt = emailRepository.findRecentEmailBySubject(accountId, msg.getSubject(), threshold);
                if (existingOpt.isPresent()) {
                    log.info("[SYNC-MATCH] Found existing email by fuzzy match: subject='{}' (ID: {}, Status: {})", 
                        msg.getSubject(), existingOpt.get().getId(), existingOpt.get().getStatus());
                }
            }
        }
        
        // SELF-SEND PROTECTION: If syncing INBOX and sender is the account's own email,
        // and we already have a SENT record for this email, SKIP creating an INBOX duplicate.
        if (existingOpt.isPresent() && "INBOX".equalsIgnoreCase(targetStatus) 
                && "SENT".equalsIgnoreCase(existingOpt.get().getStatus())) {
            // Just update IDs if needed, but do NOT change status from SENT to INBOX
            EmailEntity existing = existingOpt.get();
            boolean changed = false;
            if (existing.getGmailMessageId() == null && msg.getGmailMessageId() != null) {
                existing.setGmailMessageId(msg.getGmailMessageId());
                changed = true;
            }
            if (existing.getMessageId() == null || existing.getMessageId().startsWith("DRAFT-") || existing.getMessageId().startsWith("TEMP-")) {
                if (messageId != null && !messageId.isBlank()) {
                    existing.setMessageId(messageId);
                    changed = true;
                }
            }
            if (changed) emailRepository.save(existing);
            log.info("[SYNC-PROTECT] Skipping INBOX creation for self-sent email (existing SENT ID: {})", existing.getId());
            return null;
        }
        
        // Also skip if syncing INBOX and sender matches account email and no existing record
        if (existingOpt.isEmpty() && "INBOX".equalsIgnoreCase(targetStatus)) {
            String senderEmail = msg.getFrom();
            String accountEmail = account.getEmailAddress();
            if (senderEmail != null && accountEmail != null 
                    && senderEmail.toLowerCase().contains(accountEmail.toLowerCase())) {
                // Check if we have any SENT record with the same subject recently
                if (msg.getSubject() != null) {
                    LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
                    var sentRecord = emailRepository.findRecentEmailBySubject(accountId, msg.getSubject(), threshold);
                    if (sentRecord.isPresent() && "SENT".equalsIgnoreCase(sentRecord.get().getStatus())) {
                        log.info("[SYNC-PROTECT] Skipping INBOX creation for self-sent email by sender match (subject: '{}')", msg.getSubject());
                        return null;
                    }
                }
            }
        }
        
        if (existingOpt.isPresent()) {
            EmailEntity existing = existingOpt.get();
            boolean changed = false;

            // V36: Learn sender name if it's new/better
            learnSenderName(msg.getFrom(), msg.getFromName());

            // Update Gmail IDs if missing
            if (existing.getGmailMessageId() == null && msg.getGmailMessageId() != null) {
                existing.setGmailMessageId(msg.getGmailMessageId());
                changed = true;
            }
            if (existing.getThreadId() == null && msg.getThreadId() != null) {
                existing.setThreadId(msg.getThreadId());
                changed = true;
            }

            // CRITICAL: If we matched by gmailMessageId but the messageId is a temporary one (DRAFT-...)
            // update it to the REAL RFC822 Message-ID so future lookups are fast and stable.
            if (messageId != null && !messageId.isBlank() && 
                (existing.getMessageId() == null || existing.getMessageId().startsWith("DRAFT-") || existing.getMessageId().startsWith("TEMP-"))) {
                log.info("[SYNC-MATCH] Updating temporary messageId '{}' to real ID '{}'", existing.getMessageId(), messageId);
                existing.setMessageId(messageId);
                changed = true;
            }
            
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
                log.info("Updated email ID: {} (Body/Embedding/Status etc)", existing.getId());
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
                // PROTECTION: Never change status FROM SENT to anything else.
                // This prevents sent emails from being reclassified by IMAP sync (e.g. SENT→INBOX for self-sent, SENT→DRAFTS for race conditions)
                if ("SENT".equalsIgnoreCase(existing.getStatus())) {
                    log.info("[SYNC-PROTECT] Blocking status change from SENT to {} for email ID {}", targetStatus, existing.getId());
                } else if ("DRAFTS".equalsIgnoreCase(existing.getStatus()) && "INBOX".equalsIgnoreCase(targetStatus)) {
                    log.info("[SYNC-PROTECT] Blocking status change from DRAFTS to INBOX for email ID {} (Preventing draft jump to Inbox)", existing.getId());
                } else if (("TRASH".equalsIgnoreCase(existing.getStatus()) || "SPAM".equalsIgnoreCase(existing.getStatus())) && 
                           !"TRASH".equalsIgnoreCase(targetStatus) && !"SPAM".equalsIgnoreCase(targetStatus)) {
                    // PROTECTION: If email was recently moved to Trash/Spam (last 24 hours), don't let sync pull it back
                    // This prevents the "mail coming back to Inbox" issue if Gmail haven't processed the label change yet
                    LocalDateTime recentThreshold = LocalDateTime.now().minusHours(24);
                    if (existing.getDeletedAt() != null && existing.getDeletedAt().isAfter(recentThreshold)) {
                        log.info("[SYNC-PROTECT] Blocking provider from untrashing/unspamming recently deleted email ID {} (Provider says: {})", existing.getId(), targetStatus);
                    } else {
                        log.info("Updating status for email ID {} from {} to {} based on labels (Threshold passed)",
                            existing.getId(), existing.getStatus(), targetStatus);
                        existing.setStatus(targetStatus);
                        changed = true;
                    }
                } else {
                    log.info("Updating status for email ID {} from {} to {} based on labels",
                        existing.getId(), existing.getStatus(), targetStatus);
                    existing.setStatus(targetStatus);
                    changed = true;
                }
            }

            if (existing.isHasAttachments() != msg.isHasAttachments()) {
                existing.setHasAttachments(msg.isHasAttachments());
                changed = true;
            }

            // Sync missing or raw names/recipients for existing emails (Migration Bridge)
            boolean fromNameIsRaw = existing.getFromName() != null && existing.getFromName().equalsIgnoreCase(existing.getSender());
            if ((existing.getFromName() == null || fromNameIsRaw) && msg.getFromName() != null && !msg.getFromName().isBlank()) {
                existing.setFromName(msg.getFromName());
                changed = true;
            }

            if (msg.getTo() != null && !msg.getTo().isEmpty()) {
                String newTo = String.join(", ", msg.getTo());
                if (existing.getRecipientTo() == null || !existing.getRecipientTo().contains("<")) {
                    existing.setRecipientTo(newTo);
                    changed = true;
                }
            }

            if (msg.getCc() != null && !msg.getCc().isEmpty()) {
                String newCc = String.join(", ", msg.getCc());
                if (existing.getRecipientCc() == null || !existing.getRecipientCc().contains("<")) {
                    existing.setRecipientCc(newCc);
                    changed = true;
                }
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
                        existing.getAttachments().add(EmailAttachment.builder()
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
                try {
                    if (updatedEmailIds != null && !updatedEmailIds.contains(existing.getId())) {
                        updatedEmailIds.add(existing.getId());
                    }
                } catch (Exception ignored) {}
            }
            return null;
        }

        // New Email Creation
        // V36: Learn sender name
        learnSenderName(msg.getFrom(), msg.getFromName());

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
            return null;
        }); // End transactionTemplate.execute
        } // End synchronized
    }

    /**

     * Background task to wake up snoozed emails.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 10000)
    public void checkSnoozedEmails() {
        OffsetDateTime now = OffsetDateTime.now();
        List<EmailEntity> snoozedEmails = emailRepository.findBySnoozedUntilBeforeAndStatus(now, EmailStatus.SNOOZED);

        if (snoozedEmails.isEmpty()) {
            return;
        }

        Map<Long, List<Long>> unsnoozedByAccount = new HashMap<>();

        transactionTemplate.execute(txStatus -> {
            for (EmailEntity email : snoozedEmails) {
                log.info("Waking up email ID: {}", email.getId());
                email.setStatus(EmailStatus.INBOX);
                email.setSnoozedUntil(null);
                emailRepository.save(email);

                if (email.getAccount() != null && email.getAccount().getId() != null) {
                    unsnoozedByAccount.computeIfAbsent(email.getAccount().getId(), k -> new ArrayList<>()).add(email.getId());
                }
            }
            return null;
        });

        // Notify frontend for each affected account, including explicit email IDs so FE can fetch and insert them
        if (!unsnoozedByAccount.isEmpty()) {
            ObjectMapper mapper = new ObjectMapper();
            for (var entry : unsnoozedByAccount.entrySet()) {
                Long accountId = entry.getKey();
                List<Long> ids = entry.getValue();
                try {
                    Map<String, Object> payload = Map.of(
                            "type", "NEW_EMAILS",
                            "emailIds", ids
                    );
                    String payloadJson = mapper.writeValueAsString(payload);
                    notificationWebSocketHandler.sendRawNotification(accountId, payloadJson);
                    log.info("Sent wake-up notification with {} ids for account ID: {}", ids.size(), accountId);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize wake-up payload for account {}: {}", accountId, e.getMessage());
                    // Fallback: send a generic message so FE still knows to refresh
                    notificationWebSocketHandler.sendNotification(accountId, "NEW_EMAILS", "Email(s) returned from snooze");
                }
            }
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
    private String determineStatusFromLabels(MailMessageDto msg, Long accountId, String folderName) {
        // PRIORITY 0: Gmail Labels check for critical system states (Trash/Spam must override Sent/Drafts)
        if (msg.getLabels() != null) {
            if (containsSystemLabel(msg.getLabels(), "TRASH") || containsSystemLabel(msg.getLabels(), "\\\\TRASH")) {
                return "TRASH";
            }
            if (containsSystemLabel(msg.getLabels(), "SPAM")) {
                return "SPAM";
            }
            if (containsSystemLabel(msg.getLabels(), "DRAFT") || containsSystemLabel(msg.getLabels(), "DRAFTS")) {
                return "DRAFTS";
            }
            if (containsSystemLabel(msg.getLabels(), "SENT")) {
                return "SENT";
            }
        }

        // PRIORITY 1: IMAP Folder Name (Most reliable for system states like SENT, DRAFTS, TRASH)
        String folderStatus = determineStatusFromFolder(folderName);
        if (folderStatus != null && !EmailStatus.INBOX.equalsIgnoreCase(folderStatus)) {
            return folderStatus;
        }

        // PRIORITY 2: Gmail Labels (Used for custom Kanban columns)
        String gmailStatus = determineStatusFromGmailLabels(msg.getLabels(), accountId);
        if (gmailStatus != null && !EmailStatus.INBOX.equalsIgnoreCase(gmailStatus)) {
            return gmailStatus;
        }

        return EmailStatus.INBOX;
    }

    private String determineStatusFromGmailLabels(List<String> labels, Long accountId) {
        if (labels == null || labels.isEmpty()) {
            return EmailStatus.INBOX;
        }

        // V43: Prioritize TRASH and SPAM over others to prevent bouncing
        if (containsSystemLabel(labels, "TRASH") || containsSystemLabel(labels, "\\\\TRASH")) {
            return "TRASH";
        }
        if (containsSystemLabel(labels, "SPAM")) {
            return "SPAM";
        }
        if (containsSystemLabel(labels, "SENT")) {
            return "SENT";
        }
        if (containsSystemLabel(labels, "DRAFT") || containsSystemLabel(labels, "DRAFTS")) {
            return "DRAFTS";
        }

        // Filter out system labels (case-insensitive)
        List<String> customLabels = labels.stream()
                .filter(label -> !SYSTEM_LABELS.contains(label.toUpperCase()))
                .collect(Collectors.toList());

        if (customLabels.size() != 1) {
            return EmailStatus.INBOX;
        }

        // Exactly 1 custom label -> find or create column
        String labelName = customLabels.get(0);
        KanbanColumn column = findOrCreateColumn(accountId, labelName);
        return column.getLinkedStatus();
    }

    private boolean containsSystemLabel(List<String> labels, String target) {
        return labels.stream().anyMatch(label -> label != null && label.equalsIgnoreCase(target));
    }

    private String determineStatusFromFolder(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return EmailStatus.INBOX;
        }

        String lower = folderName.toLowerCase();
        if (lower.contains("trash") || lower.contains("deleted") || lower.contains("thùng rác")) {
            return "TRASH";
        }
        if (lower.contains("spam") || lower.contains("junk") || lower.contains("thư rác")) {
            return "SPAM";
        }
        if (lower.contains("draft") || lower.contains("bản nháp")) {
            return "DRAFTS";
        }
        if (lower.contains("sent")) {
            return "SENT";
        }
        return EmailStatus.INBOX;
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

    private List<EmailAttachment> mapAttachments(
            List<MailMessageDto.AttachmentMetadataDto> dtos, EmailEntity email) {
        return dtos.stream().map(dto -> EmailAttachment.builder()
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

    private List<EmailAttachment> mapDetailAttachments(
            List<MailMessageDetailDto.AttachmentDto> dtos, EmailEntity email) {
        log.info("[V8-ATTACH-SYNC] Mapping {} attachments for email ID: {}", dtos.size(), email.getId());
        dtos.forEach(dto -> log.info("  - CID: {}, File: {}, Inline: {}", dto.getContentId(), dto.getFilename(), dto.isInline()));
        
        return dtos.stream().map(dto -> EmailAttachment.builder()
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
    public void learnSenderName(String email, String name) {
        if (email == null || email.isBlank()) return;
        
        final String pureEmail = cleanEmail(email);
        if (pureEmail == null || pureEmail.isBlank()) return;

        // V39: Clean the name too (remove <email> if present and quotes)
        String pureName = name;
        if (pureName != null) {
            pureName = pureName.replaceAll("^\"|\"$", "").trim();
            if (pureName.contains("<") && pureName.contains(">")) {
                int start = pureName.indexOf("<");
                pureName = pureName.substring(0, start).trim();
            }
            pureName = pureName.replaceAll("^\"|\"$", "").trim();
            
            // If the name is just the email itself or the local part, treat as null
            String username = pureEmail.split("@")[0];
            if (pureName.equalsIgnoreCase(pureEmail) || pureName.equalsIgnoreCase(username) || pureName.contains("@")) {
                pureName = null;
            }
        }
        
        if (pureName != null && pureName.isBlank()) pureName = null;

        final String canonicalEmail = pureEmail.toLowerCase();
        final String finalName = pureName;
        
        emailSenderRepository.findByEmail(canonicalEmail).ifPresentOrElse(
            existing -> {
                // Update if we found a better name (non-null and better than just the email)
                if (finalName != null && (existing.getBestKnownName() == null || existing.getBestKnownName().equalsIgnoreCase(canonicalEmail))) {
                    existing.setBestKnownName(finalName);
                    emailSenderRepository.save(existing);
                    // Mass Heal: Update all existing emails from this sender in the background
                    emailRepository.updateFromNameBySender(canonicalEmail, finalName);
                }
            },
            () -> {
                emailSenderRepository.save(EmailSender.builder()
                    .email(canonicalEmail)
                    .bestKnownName(finalName)
                    .build());
                if (finalName != null) {
                    // Mass Heal: Update all existing emails from this sender in the background
                    emailRepository.updateFromNameBySender(canonicalEmail, finalName);
                }
            }
        );
    }

    private String cleanEmail(String input) {
        if (input == null) return null;
        if (input.contains("<") && input.contains(">")) {
            int start = input.indexOf("<");
            int end = input.indexOf(">");
            if (start < end) {
                return input.substring(start + 1, end).trim();
            }
        }
        return input.trim();
    }
}