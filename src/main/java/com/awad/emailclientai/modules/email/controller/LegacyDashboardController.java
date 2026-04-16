package com.awad.emailclientai.modules.email.controller;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.awad.emailclientai.modules.email.service.AiService;
import com.awad.emailclientai.modules.email.service.ImapService;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.modules.email.service.GmailLabelService;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.modules.email.dto.response.MailMessageDetailDto;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bridge Controller for legacy/drifted frontend endpoints.
 * This provides immediate stability for the Dashboard while the frontend is being updated.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class LegacyDashboardController {

    private final EmailAccountRepository emailAccountRepository;
    private final EmailRepository emailRepository;
    private final AiService aiService;
    private final ImapService imapService;
    private final EmailSyncService emailSyncService;
    private final KanbanService kanbanService;
    private final GmailLabelService gmailLabelService;
    private final com.awad.emailclientai.modules.email.service.EmailService emailService;

    @GetMapping("/check")
    public ResponseEntity<String> check() {
        return ResponseEntity.ok("LegacyBridge-v2-Defensive");
    }

    @GetMapping("/mailboxes")
    @Operation(summary = "Legacy Mailboxes Provider", description = "Bridge for frontend sidebar initialization.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMailboxes(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<Map<String, Object>> mailboxes = new ArrayList<>();
        
        int unreadCount = 0;
        try {
            EmailAccount account = getPrimaryAccount(principal);
            unreadCount = (int) emailRepository.countUnreadByAccountId(account.getId(), LocalDateTime.of(1970, 1, 1, 0, 0));
        } catch (Exception e) {
            log.warn("Could not get unread count for mailbox list: {}", e.getMessage());
        }

        mailboxes.add(createMailbox("INBOX", "Inbox", "InboxOutlined", unreadCount, "system"));
        mailboxes.add(createMailbox("STARRED", "Starred", "StarOutlined", 0, "system"));
        mailboxes.add(createMailbox("SENT", "Sent", "SendOutlined", 0, "system"));
        mailboxes.add(createMailbox("DRAFTS", "Drafts", "FileOutlined", 0, "system"));
        mailboxes.add(createMailbox("TRASH", "Trash", "DeleteOutlined", 0, "system"));
        mailboxes.add(createMailbox("SPAM", "Spam", "FolderOutlined", 0, "system"));

        Map<String, Object> data = new HashMap<>();
        data.put("mailboxes", mailboxes);
        try {
            EmailAccount account = getPrimaryAccount(principal);
            data.put("accountId", account.getId());
        } catch (Exception e) {
            log.warn("Could not include accountId in mailboxes respond: {}", e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/emails/{id}/imap-detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getImapDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        EmailEntity entity = emailRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Email not found"));

        EmailAccount primary = getPrimaryAccount(principal);
        if (!primary.getId().equals(entity.getAccount().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Not allowed to access this email"));
        }

        String folderName = "INBOX";
        if (entity.getStatus() != null && entity.getStatus().equalsIgnoreCase("SENT")) {
            if (entity.getAccount().getProvider() == EmailProvider.GMAIL) folderName = "[Gmail]/Sent Mail";
            else folderName = "Sent";
        }

        try {
            MailMessageDetailDto detail = imapService.getMessageDetail(entity.getAccount(), folderName, entity.getUid());
            Map<String, Object> data = new HashMap<>();
            data.put("detail", detail);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            log.error("Failed to fetch IMAP detail for email {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Failed to fetch IMAP detail: " + e.getMessage()));
        }
    }

    @GetMapping("/mailboxes/{id}/emails")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEmailsByMailbox(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean hasAttachments,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        
        // Map mailbox ID to status (lowercase)
        String status = id.toUpperCase();
        
        List<EmailEntity> emails = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId());
        log.info("Bridge: Fetched {} emails from DB for account ID {}", emails.size(), account.getId());
        
        String finalStatus = status;
        List<EmailEntity> filteredStream = emails.stream()
                .filter(e -> {
                    boolean match = false;
                    if ("STARRED".equalsIgnoreCase(finalStatus)) {
                        match = e.isStarred();
                    } else if ("INBOX".equalsIgnoreCase(finalStatus)) {
                        // Show ALL emails except TRASH and SPAM — same as Kanban view
                        String s = e.getStatus();
                        match = s == null || (!s.equalsIgnoreCase("TRASH") && !s.equalsIgnoreCase("SPAM"));
                    } else {
                        match = finalStatus.equalsIgnoreCase(e.getStatus());
                    }
                    
                    if (!match && log.isDebugEnabled()) {
                        log.debug("Email ID {} rejected by status filter (Status: {}, Target: {})", e.getId(), e.getStatus(), finalStatus);
                    }
                    return match;
                })
                .filter(e -> {
                    boolean match = unread == null || !unread || !e.isRead();
                    if (!match && log.isDebugEnabled()) {
                        log.debug("Email ID {} rejected by unread filter", e.getId());
                    }
                    return match;
                })
                .filter(e -> {
                    boolean match = hasAttachments == null || !hasAttachments || e.isHasAttachments();
                    if (!match && log.isDebugEnabled()) {
                        log.debug("Email ID {} rejected by attachments filter", e.getId());
                    }
                    return match;
                })
                .collect(Collectors.toList());

        // Apply Sorting
        if (sortBy != null) {
            filteredStream.sort((a, b) -> {
                int cmp = 0;
                if ("date".equals(sortBy)) {
                    if (a.getReceivedDate() == null || b.getReceivedDate() == null) cmp = 0;
                    else cmp = a.getReceivedDate().compareTo(b.getReceivedDate());
                } else if ("sender".equals(sortBy)) {
                    String s1 = a.getSender() != null ? a.getSender() : "";
                    String s2 = b.getSender() != null ? b.getSender() : "";
                    cmp = s1.compareToIgnoreCase(s2);
                }
                return "desc".equalsIgnoreCase(sortOrder) ? -cmp : cmp;
            });
        }

        List<Map<String, Object>> mapped = filteredStream.stream()
                .map(e -> this.mapToFrontendEmail(e, account))
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("emails", mapped);
        data.put("total", mapped.size());
        data.put("page", page);
        data.put("perPage", perPage);
        data.put("hasNextPage", false);
        
        log.info("Bridge: Returning {}/{} emails for mailbox {} account {} (Filters: unread={}, hasAttachments={}, sort={} {})", 
                mapped.size(), emails.size(), id, account.getId(), unread, hasAttachments, sortBy, sortOrder);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/kanban")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKanban(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean hasAttachments,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        List<EmailEntity> emails = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId());
        
        // Apply Filters
        List<EmailEntity> filtered = emails.stream()
                .filter(e -> unread == null || !unread || !e.isRead())
                .filter(e -> hasAttachments == null || !hasAttachments || e.isHasAttachments())
                .collect(Collectors.toList());

        // Apply Sorting
        if (sortBy != null) {
            filtered.sort((a, b) -> {
                int cmp = 0;
                if ("date".equals(sortBy)) {
                    if (a.getReceivedDate() == null || b.getReceivedDate() == null) cmp = 0;
                    else cmp = a.getReceivedDate().compareTo(b.getReceivedDate());
                } else if ("sender".equals(sortBy)) {
                    String s1 = a.getSender() != null ? a.getSender() : "";
                    String s2 = b.getSender() != null ? b.getSender() : "";
                    cmp = s1.compareToIgnoreCase(s2);
                }
                return "desc".equalsIgnoreCase(sortOrder) ? -cmp : cmp;
            });
        }

        Map<String, List<Map<String, Object>>> columnsData = new HashMap<>();
        
        // Fetch dynamic columns for this account
        List<KanbanColumn> columns = kanbanService.getColumns(account.getId());
        
        // Initialize columns using linkedStatus
        for (KanbanColumn col : columns) {
            String statusKey = col.getLinkedStatus() != null ? col.getLinkedStatus().toUpperCase() : "INBOX";
            columnsData.put(statusKey, new ArrayList<>());
        }

        for (EmailEntity email : filtered) {
            String status = email.getStatus() != null ? email.getStatus().toUpperCase() : "INBOX";
            if (columnsData.containsKey(status)) {
                columnsData.get(status).add(mapToKanbanCard(email, account));
            } else {
                // Fallback to INBOX if status doesn't match any dynamic column
                if (columnsData.containsKey("INBOX")) {
                    columnsData.get("INBOX").add(mapToKanbanCard(email, account));
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columnsData);
        log.info("Bridge: Returning Dynamic Kanban board with {} columns (Filters: unread={}, hasAttachments={}, sort={} {})", 
                columnsData.size(), unread, hasAttachments, sortBy, sortOrder);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/kanban/move")
    public ResponseEntity<ApiResponse<Map<String, Object>>> moveCard(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> request
    ) {
        Long emailId = Long.parseLong(request.get("email_id").toString());
        String toStatus = request.get("to_status").toString();
        
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        email.setStatus(toStatus);
        
        if (request.containsKey("kanban_order")) {
            email.setKanbanOrder(Double.parseDouble(request.get("kanban_order").toString()));
        }
        
        emailRepository.save(email);
        EmailAccount account = getPrimaryAccount(principal);
        
        return ResponseEntity.ok(ApiResponse.success(mapToKanbanCard(email, account)));
    }

    @PostMapping("/kanban/summarize")
    public ResponseEntity<Map<String, Object>> summarizeCard(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request
    ) {
        Long emailId = Long.parseLong(request.get("email_id"));
        String summary = aiService.summarizeEmail(emailId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("summary", summary);
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/kanban/snooze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> snoozeCard(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request
    ) {
        Long emailId = Long.parseLong(request.get("email_id"));
        String untilStr = request.get("until");
        
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        email.setStatus(EmailStatus.SNOOZED);
        // Parse ISO 8601 string (e.g. 2024-03-27T10:00:00.000Z)
        email.setSnoozedUntil(OffsetDateTime.parse(untilStr));
        
        emailRepository.save(email);
        EmailAccount account = getPrimaryAccount(principal);
        
        return ResponseEntity.ok(ApiResponse.success(mapToKanbanCard(email, account)));
    }

    @GetMapping("/kanban/meta")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKanbanMeta(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        List<KanbanColumn> columns = kanbanService.getColumns(account.getId());
        
        List<Map<String, String>> mappedColumns = columns.stream()
                .map(col -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("key", col.getLinkedStatus() != null ? col.getLinkedStatus().toUpperCase() : "INBOX");
                    m.put("label", col.getName());
                    m.put("color", "#f1f5f9"); // Default slate-100 color
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("columns", mappedColumns);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/emails/{id}/modify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> modifyEmail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, List<String>> request
    ) {
        Long emailId = Long.parseLong(id);
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        List<String> addLabels = request.getOrDefault("addLabels", new ArrayList<>());
        List<String> removeLabels = request.getOrDefault("removeLabels", new ArrayList<>());
        
        boolean changed = false;
        
        if (addLabels.contains("STARRED")) {
            email.setStarred(true);
            changed = true;
        }
        
        if (removeLabels.contains("STARRED")) {
            email.setStarred(false);
            // Also reset legacy STARRED status if present
            if ("STARRED".equalsIgnoreCase(email.getStatus())) {
                email.setStatus(EmailStatus.INBOX);
            }
            changed = true;
        }
        
        if (removeLabels.contains("UNREAD")) {
            email.setRead(true);
            changed = true;
        }
        
        if (addLabels.contains("TRASH")) {
            email.setStatus("TRASH");
            changed = true;
        }
        
        if (changed) {
            emailRepository.save(email);
            
            // Sync to Gmail
            try {
                if (removeLabels.contains("UNREAD")) {
                    imapService.setMessageRead(email.getAccount(), "INBOX", email.getUid(), true);
                } else if (addLabels.contains("UNREAD")) {
                    imapService.setMessageRead(email.getAccount(), "INBOX", email.getUid(), false);
                }
                
                if (addLabels.contains("STARRED")) {
                    imapService.setMessageStarred(email.getAccount(), "INBOX", email.getUid(), true);
                } else if (removeLabels.contains("STARRED")) {
                    imapService.setMessageStarred(email.getAccount(), "INBOX", email.getUid(), false);
                }

                if (addLabels.contains("TRASH")) {
                    imapService.trashMessage(email.getAccount(), "INBOX", email.getUid());
                    log.info("Successfully trashed email (UID: {}) from Gmail", email.getUid());
                    // Crucial: remove from local DB so it doesn't re-sync from Inbox
                    emailRepository.delete(email);
                    log.info("Deleted local email record for UID: {}", email.getUid());
                }
            } catch (Exception e) {
                log.error("Failed to sync flags/deletion to Gmail for email {}: {}", email.getUid(), e.getMessage());
            }
        }
        
        EmailAccount account = getPrimaryAccount(principal);
        return ResponseEntity.ok(ApiResponse.success(mapToFrontendEmail(email, account)));
    }


    @GetMapping("/gmail/labels")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGmailLabels(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        List<Map<String, String>> labels = gmailLabelService.getLabels(account);
        
        Map<String, Object> data = new HashMap<>();
        data.put("labels", labels);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/gmail/labels")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createGmailLabel(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request
    ) {
        String labelName = request.get("name");
        if (labelName == null || labelName.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Label name is required"));
        }

        EmailAccount account = getPrimaryAccount(principal);
        Map<String, String> created = gmailLabelService.createLabel(account, labelName);
        
        Map<String, Object> data = new HashMap<>();
        data.put("label", created);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/search/generate-embeddings")
    @Operation(summary = "Active Embedding Generator", description = "Generates missing embeddings for the current user's emails.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateEmbeddings(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        
        // Fetch a batch of emails missing embeddings
        List<EmailEntity> missing = emailRepository.findEmailsMissingEmbeddings(account.getId(), PageRequest.of(0, 50));
        
        log.info("Generating embeddings for {} emails (Account: {})", missing.size(), account.getEmailAddress());
        
        int processed = 0;
        int failed = 0;
        
        for (EmailEntity entity : missing) {
            try {
                // To avoid 429 Too Many Requests from Gemini Free Tier (15 RPM)
                Thread.sleep(4000); 
                
                // This method strips HTML internally and handles storage
                emailSyncService.refreshEmail(entity.getId());
                processed++;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Failed to generate embedding for email {}: {}", entity.getId(), e.getMessage());
                failed++;
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("processed", processed);
        result.put("failed", failed);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // Helper methods
    private EmailAccount getPrimaryAccount(UserPrincipal principal) {
        return emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No active email account linked. Please link your Gmail first."));
    }

    private Map<String, Object> createMailbox(String id, String name, String icon, int unread, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("unreadCount", unread);
        m.put("type", type);
        return m;
    }


    private Map<String, Object> mapToFrontendEmail(EmailEntity entity, EmailAccount activeAccount) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("id", entity.getId().toString());
            m.put("messageId", entity.getMessageId());
            m.put("threadId", entity.getThreadId() != null ? entity.getThreadId() : entity.getMessageId());
            m.put("gmailMessageId", entity.getGmailMessageId());
            m.put("accountEmail", activeAccount != null ? activeAccount.getEmailAddress() : entity.getAccount().getEmailAddress());
            m.put("mailboxId", entity.getStatus() != null ? entity.getStatus() : "INBOX");
            
            // Generate gmailLink
            String emailAddr = activeAccount != null ? activeAccount.getEmailAddress() : entity.getAccount().getEmailAddress();
            String encodedEmail = URLEncoder.encode(emailAddr, StandardCharsets.UTF_8);
                String gmailLink = entity.getGmailMessageId() != null ?
                    String.format("https://mail.google.com/mail/u/0/?authuser=%s#inbox/%s", encodedEmail, entity.getGmailMessageId()) :
                    String.format("https://mail.google.com/mail/u/0/?authuser=%s#search/rfc822msgid:%s",
                        encodedEmail, URLEncoder.encode(entity.getMessageId(), StandardCharsets.UTF_8));
            m.put("gmailLink", gmailLink);
            
            // Map from: { name, email }
            Map<String, String> from = new HashMap<>();
            String sender = entity.getSender() != null ? entity.getSender() : "Unknown <unknown@example.com>";
            if (sender.contains("<")) {
                int open = sender.indexOf("<");
                int close = sender.indexOf(">");
                from.put("name", sender.substring(0, open).trim());
                from.put("email", sender.substring(open + 1, close).trim());
            } else {
                from.put("name", sender);
                from.put("email", sender);
            }
            if (from.get("name").isEmpty()) from.put("name", from.get("email"));
            m.put("from", from);
            
            // Labels placeholder; recipients and attachments populated from DTO below
            m.put("labels", new ArrayList<>());
            
            m.put("subject", entity.getSubject() != null ? entity.getSubject() : "(No Subject)");
            m.put("preview", entity.getSnippet() != null ? entity.getSnippet() : "");
            
            // Process body via EmailService to ensure sanitization and CID resolution
            String processedBody = emailService.processEmailBody(entity.getBody(), entity.getId(), entity.getAttachments());
            m.put("body", processedBody != null ? processedBody : "");
            
            m.put("isRead", entity.isRead());
            m.put("isStarred", entity.isStarred());
            
            // V10.36: Use mapToDto's comprehensive logic to calculate correct flags and cloud links
                com.awad.emailclientai.modules.email.dto.response.EmailEntityDto dto = emailService.mapToDto(entity);

                // Populate recipient lists for list-view to avoid requiring an extra detail fetch
                java.util.List<String> toList = (dto.getRecipientTo() != null && !dto.getRecipientTo().isEmpty()) ? dto.getRecipientTo()
                    : (dto.getTo() != null ? dto.getTo().stream().map(a -> a.getEmail()).collect(Collectors.toList()) : new java.util.ArrayList<>());
                java.util.List<String> ccList = (dto.getRecipientCc() != null && !dto.getRecipientCc().isEmpty()) ? dto.getRecipientCc()
                    : (dto.getCc() != null ? dto.getCc().stream().map(a -> a.getEmail()).collect(Collectors.toList()) : new java.util.ArrayList<>());

                m.put("to", toList);
                m.put("cc", ccList);
                m.put("bcc", new java.util.ArrayList<>());

                // Include attachments metadata so the UI can show download/open buttons without extra fetch
                m.put("attachments", dto.getAttachments() != null ? dto.getAttachments() : new java.util.ArrayList<>());

                m.put("hasAttachments", dto.isHasAttachments());
                m.put("hasCloudLinks", dto.isHasCloudLinks());
                m.put("hasPhysicalAttachments", dto.isHasPhysicalAttachments());
            
            String dateStr;
            if (entity.getReceivedDate() != null) {
                dateStr = entity.getReceivedDate().atZone(ZoneId.systemDefault()).toInstant().toString();
            } else {
                dateStr = "2024-01-01T00:00:00Z";
            }
            m.put("receivedAt", dateStr);
            m.put("createdAt", dateStr);
            m.put("summary", entity.getSummary());
            
            return m;
        } catch (Exception e) {
            log.error("Error mapping email ID {}: {}", entity.getId(), e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("id", entity.getId().toString());
            fallback.put("from", Map.of("name", "Error", "email", "error@example.com"));
            fallback.put("subject", "Error mapping this email");
            return fallback;
        }
    }

    private Map<String, Object> mapToKanbanCard(EmailEntity email, EmailAccount activeAccount) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", email.getId().toString());
        card.put("message_id", email.getMessageId());
        card.put("gmail_message_id", email.getGmailMessageId());
        card.put("thread_id", email.getThreadId());
        card.put("account_email", activeAccount != null ? activeAccount.getEmailAddress() : email.getAccount().getEmailAddress());
        card.put("sender", email.getSender());
        card.put("subject", email.getSubject());
        card.put("summary", email.getSummary());
        card.put("preview", email.getSnippet());
        
        String encodedEmail = URLEncoder.encode(email.getAccount().getEmailAddress(), StandardCharsets.UTF_8);
        String gmailUrl = email.getGmailMessageId() != null ?
            String.format("https://mail.google.com/mail/u/0/?authuser=%s#inbox/%s", encodedEmail, email.getGmailMessageId()) :
            String.format("https://mail.google.com/mail/u/0/?authuser=%s#search/rfc822msgid:%s", 
                encodedEmail, URLEncoder.encode(email.getMessageId(), StandardCharsets.UTF_8));

        card.put("gmail_url", gmailUrl);
        card.put("received_at", email.getReceivedDate() != null ? email.getReceivedDate().atZone(ZoneId.systemDefault()).toInstant().toString() : "");
        card.put("is_read", email.isRead());
        card.put("is_starred", email.isStarred());
        
        com.awad.emailclientai.modules.email.dto.response.EmailEntityDto dto = emailService.mapToDto(email);
        card.put("has_attachments", dto.isHasAttachments());
        card.put("has_cloud_links", dto.isHasCloudLinks());
        card.put("has_physical_attachments", dto.isHasPhysicalAttachments());
        
        return card;
    }
}
