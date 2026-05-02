package com.awad.emailclientai.modules.email.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.awad.emailclientai.modules.email.service.NotificationWebSocketHandler;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final ObjectMapper objectMapper;

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
        int draftsCount = 0;
        int starredCount = 0;
        int trashCount = 0;
        int spamCount = 0;

        try {
            EmailAccount account = getPrimaryAccount(principal);
            unreadCount = (int) emailRepository.countUnreadByAccountId(account.getId(), LocalDateTime.of(1970, 1, 1, 0, 0));
            starredCount = (int) emailRepository.countStarredByAccountId(account.getId(), LocalDateTime.of(1970, 1, 1, 0, 0));
            
            draftsCount = (int) (emailRepository.countByAccountIdAndStatus(account.getId(), "DRAFTS") + 
                               emailRepository.countByAccountIdAndStatus(account.getId(), "DRAFT"));
            
            trashCount = (int) emailRepository.countByAccountIdAndStatus(account.getId(), "TRASH");
            spamCount = (int) emailRepository.countByAccountIdAndStatus(account.getId(), "SPAM");
            long sentCount = emailRepository.countByAccountIdAndStatus(account.getId(), "SENT");

            mailboxes.add(createMailbox("INBOX", "Inbox", "InboxOutlined", unreadCount, "system"));
            mailboxes.add(createMailbox("STARRED", "Starred", "StarOutlined", starredCount, "system"));
            mailboxes.add(createMailbox("SENT", "Sent", "SendOutlined", (int) sentCount, "system"));
            mailboxes.add(createMailbox("DRAFTS", "Drafts", "FileOutlined", draftsCount, "system"));
            mailboxes.add(createMailbox("TRASH", "Trash", "DeleteOutlined", trashCount, "system"));
            mailboxes.add(createMailbox("SPAM", "Spam", "FolderOutlined", spamCount, "system"));
        } catch (Exception e) {
            log.warn("Could not get counts for mailbox list: {}", e.getMessage());
            // Ensure we at least have basic mailboxes even on failure
            if (mailboxes.isEmpty()) {
                mailboxes.add(createMailbox("INBOX", "Inbox", "InboxOutlined", 0, "system"));
            }
        }

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
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEmailsByMailbox(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean hasAttachments,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage,
            @RequestParam(required = false) List<String> sort 
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        String status = id.toUpperCase();
        
        log.info("[API-REQUEST] getEmailsByMailbox: mailboxId={}, status={}, accountId={}", id, status, account.getId());
        
        // Final sort list construction
        List<String> sortList = (sort == null || sort.isEmpty()) 
            ? List.of("receivedDate:desc") 
            : sort;

        // V43: Fetch filtered data directly from DB for performance and consistency
        List<EmailEntity> filteredStream;
        if ("INBOX".equalsIgnoreCase(status)) {
            // Inbox is a special "folder" that excludes Sent, Drafts, Trash, Spam
            filteredStream = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId()).stream()
                .filter(e -> {
                    String s = e.getStatus();
                    // V43: NULL status should be treated as INBOX
                    if (s == null) return true;
                    return !s.equalsIgnoreCase("SENT") && !s.equalsIgnoreCase("DRAFTS") && !s.equalsIgnoreCase("DRAFT") && !s.equalsIgnoreCase("TRASH") && !s.equalsIgnoreCase("SPAM");
                }).collect(Collectors.toList());
        } else if ("STARRED".equalsIgnoreCase(status)) {
            filteredStream = emailRepository.findStarredByAccountId(account.getId());
        } else if ("DRAFTS".equalsIgnoreCase(status) || "DRAFT".equalsIgnoreCase(status)) {
            filteredStream = emailRepository.findAllByAccountIdAndStatus(account.getId(), "DRAFTS");
            if (filteredStream.isEmpty()) {
                filteredStream = emailRepository.findAllByAccountIdAndStatus(account.getId(), "DRAFT");
            }
        } else {
            filteredStream = emailRepository.findAllByAccountIdAndStatus(account.getId(), status);
        }

        // Apply secondary filters
        List<EmailEntity> processedList = filteredStream.stream()
                .filter(e -> unread == null || !unread || !e.isRead())
                .filter(e -> hasAttachments == null || !hasAttachments || e.isHasAttachments())
                .collect(Collectors.toList());

        // Apply Multi-Layer Sorting
        processedList.sort((a, b) -> {
            for (String s : sortList) {
                String[] parts = s.split(":");
                String field = parts[0];
                String order = parts.length > 1 ? parts[1] : "desc";
                
                int cmp = 0;
                if ("date".equals(field) || "receivedDate".equals(field)) {
                    if (a.getReceivedDate() == null || b.getReceivedDate() == null) cmp = 0;
                    else cmp = a.getReceivedDate().compareTo(b.getReceivedDate());
                } else if ("fromName".equals(field) || "sender".equals(field)) {
                    String n1 = (a.getFromName() != null && !a.getFromName().isBlank()) ? a.getFromName() : (a.getSender() != null ? a.getSender() : "");
                    String n2 = (b.getFromName() != null && !b.getFromName().isBlank()) ? b.getFromName() : (b.getSender() != null ? b.getSender() : "");
                    cmp = n1.compareToIgnoreCase(n2);
                } else if ("subject".equals(field)) {
                    String sub1 = (a.getSubject() != null ? a.getSubject() : "").replaceAll("&#039;", "\u0027").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").replaceAll("^(?i)(Re|Fwd|Fw):\\s*", "").replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
                    String sub2 = (b.getSubject() != null ? b.getSubject() : "").replaceAll("&#039;", "\u0027").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").replaceAll("^(?i)(Re|Fwd|Fw):\\s*", "").replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
                    cmp = sub1.compareToIgnoreCase(sub2);
                }
                
                if (cmp != 0) {
                    return "desc".equalsIgnoreCase(order) ? -cmp : cmp;
                }
            }
            return 0;
        });

        int total = processedList.size();
        int fromIndex = Math.max(0, (page - 1) * perPage);
        int toIndex = Math.min(fromIndex + perPage, total);
        List<Map<String, Object>> pageSlice = fromIndex < total ? processedList.subList(fromIndex, toIndex).stream()
            .map(e -> this.mapToFrontendEmail(e, account, principal)).collect(Collectors.toList()) : new ArrayList<>();

        Map<String, Object> data = new HashMap<>();
        data.put("emails", pageSlice);
        data.put("total", total);
        data.put("page", page);
        data.put("perPage", perPage);
        data.put("hasNextPage", toIndex < total);

        log.info("[API-RESPONSE] getEmailsByMailbox: mailboxId={}, found={} emails, total={}", id, pageSlice.size(), total);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/mailboxes/{id}/kanban")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKanban(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(required = false) List<String> sort
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        String status = id.toUpperCase();
        List<EmailEntity> emails = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId());
        
        List<String> sortList = (sort == null || sort.isEmpty()) ? List.of("receivedDate:desc") : sort;

        String finalStatus = status;
        List<EmailEntity> filtered = emails.stream()
                .filter(e -> {
                    if ("INBOX".equalsIgnoreCase(finalStatus)) {
                        String s = e.getStatus();
                        return s != null && !s.equalsIgnoreCase("SENT") && !s.equalsIgnoreCase("DRAFTS") && !s.equalsIgnoreCase("TRASH") && !s.equalsIgnoreCase("SPAM");
                    }
                    return finalStatus.equalsIgnoreCase(e.getStatus());
                })
                .collect(Collectors.toList());

        // Apply Multi-Layer Sorting
        filtered.sort((a, b) -> {
            for (String s : sortList) {
                String[] parts = s.split(":");
                String field = parts[0];
                String order = parts.length > 1 ? parts[1] : "desc";
                int cmp = 0;
                if ("date".equals(field) || "receivedDate".equals(field)) {
                    if (a.getReceivedDate() == null || b.getReceivedDate() == null) cmp = 0;
                    else cmp = a.getReceivedDate().compareTo(b.getReceivedDate());
                } else if ("fromName".equals(field) || "sender".equals(field)) {
                    String n1 = (a.getFromName() != null && !a.getFromName().isBlank()) ? a.getFromName() : (a.getSender() != null ? a.getSender() : "");
                    String n2 = (b.getFromName() != null && !b.getFromName().isBlank()) ? b.getFromName() : (b.getSender() != null ? b.getSender() : "");
                    cmp = n1.compareToIgnoreCase(n2);
                } else if ("subject".equals(field)) {
                    String sub1 = (a.getSubject() != null ? a.getSubject() : "").replaceAll("&#039;", "\u0027").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").replaceAll("^(?i)(Re|Fwd|Fw):\\s*", "").replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
                    String sub2 = (b.getSubject() != null ? b.getSubject() : "").replaceAll("&#039;", "\u0027").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").replaceAll("^(?i)(Re|Fwd|Fw):\\s*", "").replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
                    cmp = sub1.compareToIgnoreCase(sub2);
                }
                if (cmp != 0) return "desc".equalsIgnoreCase(order) ? -cmp : cmp;
            }
            return 0;
        });

        // Map to Columns
        Map<String, List<Map<String, Object>>> columnsData = new HashMap<>();
        List<KanbanColumn> columns = kanbanService.getColumns(account.getId());
        for (KanbanColumn col : columns) {
            String statusKey = col.getLinkedStatus() != null ? col.getLinkedStatus().toUpperCase() : "INBOX";
            columnsData.put(statusKey, new ArrayList<>());
        }

        for (EmailEntity email : filtered) {
            String eStatus = email.getStatus() != null ? email.getStatus().toUpperCase() : "INBOX";
            if (columnsData.containsKey(eStatus)) {
                columnsData.get(eStatus).add(mapToKanbanCard(email, account));
            } else if (columnsData.containsKey("INBOX")) {
                columnsData.get("INBOX").add(mapToKanbanCard(email, account));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columnsData);
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
        
        String previousStatus = email.getStatus();
        email.setStatus(toStatus);
        
        // Handle Trash logic
        if ("TRASH".equalsIgnoreCase(toStatus)) {
            email.setPreviousStatus(previousStatus);
            email.setDeletedAt(LocalDateTime.now());
        } else if ("TRASH".equalsIgnoreCase(previousStatus)) {
            // Restoring from trash
            email.setDeletedAt(null);
        }

        if (request.containsKey("kanban_order")) {
            email.setKanbanOrder(Double.parseDouble(request.get("kanban_order").toString()));
        }
        
        emailRepository.save(email);
        
        emailService.syncStatusToProvider(email.getId(), previousStatus, toStatus);
        
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

    @PostMapping("/emails/bulk-modify")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkModifyEmails(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> request
    ) {
        List<Long> emailIds = null;
        if (request.get("ids") != null) {
            emailIds = ((List<?>) request.get("ids")).stream()
                    .map(id -> Long.parseLong(id.toString()))
                    .collect(Collectors.toList());
        }
        
        String mailboxId = (String) request.get("mailboxId");
        Boolean unreadOnly = (Boolean) request.get("unread");
        Boolean hasAttachments = (Boolean) request.get("hasAttachments");
        
        @SuppressWarnings("unchecked")
        Map<String, List<String>> actions = (Map<String, List<String>>) request.get("actions");
        List<String> addLabels = actions.getOrDefault("addLabels", new ArrayList<>());
        List<String> removeLabels = actions.getOrDefault("removeLabels", new ArrayList<>());
        List<String> normalizedAdd = addLabels.stream().map(v -> v == null ? "" : v.toUpperCase(Locale.ROOT)).collect(Collectors.toList());
        List<String> normalizedRemove = removeLabels.stream().map(v -> v == null ? "" : v.toUpperCase(Locale.ROOT)).collect(Collectors.toList());

        EmailAccount account = getPrimaryAccount(principal);
        List<EmailEntity> emails;
        
        if (emailIds != null && !emailIds.isEmpty()) {
            emails = emailRepository.findAllById(emailIds);
        } else if (mailboxId != null) {
            log.info("[V50-BULK] Processing by filter: mailbox={}, unread={}, attachments={}", mailboxId, unreadOnly, hasAttachments);
            // Re-use logic from getEmailsByMailbox but for modification
            String status = mailboxId.toUpperCase();
            List<EmailEntity> stream;
            if ("INBOX".equalsIgnoreCase(status)) {
                stream = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId()).stream()
                    .filter(e -> {
                        String s = e.getStatus();
                        if (s == null) return true;
                        return !s.equalsIgnoreCase("SENT") && !s.equalsIgnoreCase("DRAFTS") && !s.equalsIgnoreCase("DRAFT") && !s.equalsIgnoreCase("TRASH") && !s.equalsIgnoreCase("SPAM");
                    }).collect(Collectors.toList());
            } else if ("STARRED".equalsIgnoreCase(status)) {
                stream = emailRepository.findStarredByAccountId(account.getId());
            } else if ("DRAFTS".equalsIgnoreCase(status) || "DRAFT".equalsIgnoreCase(status)) {
                stream = emailRepository.findAllByAccountIdAndStatus(account.getId(), "DRAFTS");
                if (stream.isEmpty()) stream = emailRepository.findAllByAccountIdAndStatus(account.getId(), "DRAFT");
            } else {
                stream = emailRepository.findAllByAccountIdAndStatus(account.getId(), status);
            }
            emails = stream.stream()
                    .filter(e -> unreadOnly == null || !unreadOnly || !e.isRead())
                    .filter(e -> hasAttachments == null || !hasAttachments || e.isHasAttachments())
                    .collect(Collectors.toList());
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error("Either ids or mailboxId must be provided"));
        }

        log.info("[V50-BULK] Bulk modify request for {} emails. Add: {}, Remove: {}", emails.size(), normalizedAdd, normalizedRemove);
        int updatedCount = 0;
        List<Long> updatedIds = new ArrayList<>();
        Map<Long, String> previousStatuses = new HashMap<>();

        for (EmailEntity email : emails) {
            String previousStatus = email.getStatus();
            boolean changed = false;

            if (normalizedAdd.contains("STARRED")) { email.setStarred(true); changed = true; }
            if (normalizedRemove.contains("STARRED")) { 
                email.setStarred(false); 
                if ("STARRED".equalsIgnoreCase(email.getStatus())) email.setStatus(EmailStatus.INBOX);
                changed = true; 
            }
            if (normalizedRemove.contains("UNREAD")) { email.setRead(true); changed = true; }
            if (normalizedAdd.contains("UNREAD")) { email.setRead(false); changed = true; }
            if (normalizedAdd.contains("SPAM")) { email.setStatus("SPAM"); changed = true; }
            if (normalizedAdd.contains("TRASH")) {
                email.setPreviousStatus(email.getStatus());
                email.setDeletedAt(LocalDateTime.now());
                email.setStatus("TRASH");
                changed = true;
            }
            if (normalizedAdd.contains("INBOX")) {
                if (email.getPreviousStatus() != null && !email.getPreviousStatus().equalsIgnoreCase("TRASH")) {
                    email.setStatus(email.getPreviousStatus());
                } else {
                    email.setStatus(EmailStatus.INBOX);
                }
                email.setDeletedAt(null);
                changed = true;
            }

            if (changed) {
                emailRepository.save(email);
                updatedCount++;
                updatedIds.add(email.getId());
                previousStatuses.put(email.getId(), previousStatus);
            }
        }

        // V51: Register a SINGLE synchronization for the entire batch
        if (updatedCount > 0) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 1. Bulk Notify UI
                        try {
                            Map<String, Object> msg = new HashMap<>();
                            msg.put("type", "UPDATED_EMAILS");
                            msg.put("emailIds", updatedIds);
                            msg.put("accountId", account.getId());
                            notificationWebSocketHandler.sendRawNotification(account.getId(), objectMapper.writeValueAsString(msg));
                            log.info("[V51-BULK-NOTIFY] Sent post-commit notification for {} emails", updatedIds.size());
                        } catch (Exception e) {
                            log.warn("Failed to send bulk WebSocket notification: {}", e.getMessage());
                        }

                        // 2. Trigger async sync for each email
                        for (Long id : updatedIds) {
                            emailService.syncFlagsAndLabelsToProvider(id, previousStatuses.get(id), normalizedAdd, normalizedRemove);
                        }
                    }
                });
            } else {
                // Fallback for non-transactional
                for (Long id : updatedIds) {
                    emailService.syncFlagsAndLabelsToProvider(id, previousStatuses.get(id), normalizedAdd, normalizedRemove);
                }
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("updatedCount", updatedCount);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/emails/{id}/modify")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> modifyEmail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, List<String>> request
    ) {
        Long emailId = Long.parseLong(id);
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        String previousStatus = email.getStatus();
        
        List<String> addLabels = request.getOrDefault("addLabels", new ArrayList<>());
        List<String> removeLabels = request.getOrDefault("removeLabels", new ArrayList<>());
        List<String> normalizedAdd = addLabels.stream().map(v -> v == null ? "" : v.toUpperCase(Locale.ROOT)).collect(Collectors.toList());
        List<String> normalizedRemove = removeLabels.stream().map(v -> v == null ? "" : v.toUpperCase(Locale.ROOT)).collect(Collectors.toList());
        
        boolean changed = false;
        
        if (normalizedAdd.contains("STARRED")) {
            email.setStarred(true);
            changed = true;
        }
        
        if (normalizedRemove.contains("STARRED")) {
            email.setStarred(false);
            // Also reset legacy STARRED status if present
            if ("STARRED".equalsIgnoreCase(email.getStatus())) {
                email.setStatus(EmailStatus.INBOX);
            }
            changed = true;
        }
        
        if (normalizedRemove.contains("UNREAD")) {
            email.setRead(true);
            changed = true;
        }

        if (normalizedAdd.contains("UNREAD")) {
            email.setRead(false);
            changed = true;
        }

        if (normalizedAdd.contains("SPAM")) {
            email.setStatus("SPAM");
            changed = true;
        }
        
        if (normalizedAdd.contains("TRASH")) {
            email.setPreviousStatus(email.getStatus());
            email.setDeletedAt(LocalDateTime.now());
            email.setStatus("TRASH");
            changed = true;
        }

        if (normalizedAdd.contains("INBOX")) {
            // Restore logic: return to previous status if available (e.g. DRAFTS)
            if (email.getPreviousStatus() != null && !email.getPreviousStatus().equalsIgnoreCase("TRASH")) {
                email.setStatus(email.getPreviousStatus());
            } else {
                email.setStatus(EmailStatus.INBOX);
            }
            email.setDeletedAt(null);
            changed = true;
        }
        
        if (changed) {
            emailRepository.saveAndFlush(email);
            
            // V43: Use TransactionSynchronization to ensure both WebSocket notification AND async sync start ONLY after DB commit
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // Notify UI only after commit so re-fetch sees latest state
                        try {
                            Map<String, Object> msg = new HashMap<>();
                            msg.put("type", "UPDATED_EMAILS");
                            msg.put("emailIds", List.of(email.getId()));
                            msg.put("accountId", email.getAccount().getId());
                            
                            String jsonPayload = objectMapper.writeValueAsString(msg);
                            notificationWebSocketHandler.sendRawNotification(email.getAccount().getId(), jsonPayload);
                            log.info("[V49-NOTIFY] Sent post-commit notification for email ID: {}", email.getId());
                        } catch (Exception e) {
                            log.warn("Failed to send WebSocket notification: {}", e.getMessage());
                        }
                        
                        emailService.syncFlagsAndLabelsToProvider(email.getId(), previousStatus, normalizedAdd, normalizedRemove);
                    }
                });
            } else {
                // Fallback for non-transactional (rare but safe)
                try {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", "UPDATED_EMAILS");
                    msg.put("emailIds", List.of(email.getId()));
                    msg.put("accountId", email.getAccount().getId());
                    notificationWebSocketHandler.sendRawNotification(email.getAccount().getId(), objectMapper.writeValueAsString(msg));
                } catch (Exception e) {}
                emailService.syncFlagsAndLabelsToProvider(email.getId(), previousStatus, normalizedAdd, normalizedRemove);
            }
        }
        
        EmailAccount account = getPrimaryAccount(principal);
        return ResponseEntity.ok(ApiResponse.success(mapToFrontendEmail(email, account, principal)));
    }

    @DeleteMapping("/emails/bulk-delete")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteEmails(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> request
    ) {
        List<Long> ids = null;
        if (request.get("ids") != null) {
            ids = ((List<?>) request.get("ids")).stream()
                    .map(id -> Long.parseLong(id.toString()))
                    .collect(Collectors.toList());
        }
        
        String mailboxId = (String) request.get("mailboxId");
        Boolean unreadOnly = (Boolean) request.get("unread");
        Boolean hasAttachments = (Boolean) request.get("hasAttachments");
        EmailAccount account = getPrimaryAccount(principal);

        if (ids != null && !ids.isEmpty()) {
            log.info("[V50-BULK] Bulk permanent delete request for {} email IDs", ids.size());
            for (Long id : ids) {
                emailService.deleteEmailPermanently(id);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("deletedCount", ids.size());
            return ResponseEntity.ok(ApiResponse.success(data));
        } else if (mailboxId != null) {
            // Find all matching emails to delete permanently
            String status = mailboxId.toUpperCase();
            List<EmailEntity> emails = emailRepository.findAllByAccountIdAndStatus(account.getId(), status).stream()
                    .filter(e -> unreadOnly == null || !unreadOnly || !e.isRead())
                    .filter(e -> hasAttachments == null || !hasAttachments || e.isHasAttachments())
                    .collect(Collectors.toList());
            
            log.info("[V50-BULK] Bulk permanent delete request for all {} matching emails in {}", emails.size(), mailboxId);
            for (EmailEntity e : emails) {
                emailService.deleteEmailPermanently(e.getId());
            }
            Map<String, Object> data = new HashMap<>();
            data.put("deletedCount", emails.size());
            return ResponseEntity.ok(ApiResponse.success(data));
        }

        return ResponseEntity.badRequest().body(ApiResponse.error("Either ids or mailboxId must be provided"));
    }

    @DeleteMapping("/emails/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Void>> deleteEmail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        if (!email.getAccount().getId().equals(account.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        emailService.deleteEmailPermanently(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/mailboxes/TRASH/empty")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Void>> emptyTrash(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        emailService.emptyTrash(account);
        return ResponseEntity.ok(ApiResponse.success("Empty trash process started in background"));
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


    private Map<String, Object> mapToFrontendEmail(EmailEntity entity, EmailAccount activeAccount, UserPrincipal principal) {
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
            
            // V42: Robust Sender Mapping
            Map<String, Object> from = new HashMap<>();
            String rawSender = entity.getSender() != null ? entity.getSender() : "Unknown <unknown@example.com>";
            String senderEmail = "";
            String senderName = entity.getFromName();

            // Extract email address
            if (rawSender.contains("<") && rawSender.contains(">")) {
                int open = rawSender.indexOf("<");
                int close = rawSender.indexOf(">");
                senderEmail = rawSender.substring(open + 1, close).trim();
                if (senderName == null || senderName.isBlank() || senderName.equalsIgnoreCase(senderEmail)) {
                    senderName = rawSender.substring(0, open).trim();
                    senderName = senderName.replaceAll("^\"|\"$", "").trim();
                }
            } else {
                senderEmail = rawSender.trim();
                if (senderName == null || senderName.isBlank()) senderName = senderEmail;
            }

            // Universal "You" recognition
            boolean isFromMe = false;
            if (activeAccount != null && senderEmail.equalsIgnoreCase(activeAccount.getEmailAddress())) {
                isFromMe = true;
                senderName = activeAccount.getDisplayName();
            } else if (principal != null && senderEmail.equalsIgnoreCase(principal.getEmail())) {
                isFromMe = true;
                senderName = principal.getName();
            }
            
            // Final Cleanup
            if (senderName == null || senderName.isBlank() || senderName.equalsIgnoreCase(senderEmail)) {
                senderName = senderEmail.split("@")[0];
            }

            from.put("name", senderName);
            from.put("email", senderEmail);
            m.put("from", from);
            m.put("fromName", senderName);
            m.put("isFromMe", isFromMe);
            
            // Labels placeholder; recipients and attachments populated from DTO below
            m.put("labels", new ArrayList<>());
            
            m.put("subject", entity.getSubject() != null ? entity.getSubject() : "(No Subject)");
            m.put("preview", entity.getSnippet() != null ? entity.getSnippet() : "");
            
            // Process body via EmailService to ensure sanitization and CID resolution
            String processedBody = emailService.processEmailBody(entity.getBody(), entity.getId(), entity.getAttachments());
            m.put("body", processedBody != null ? processedBody : "");
            
            m.put("isRead", entity.isRead());
            m.put("isStarred", entity.isStarred());
            
            // Optimization: Avoid calling mapToDto() here as it's too heavy and causes state corruption in some Hibernate versions
            m.put("to", entity.getRecipientTo() != null ? java.util.Arrays.asList(entity.getRecipientTo().split(",\\s*")) : new ArrayList<>());
            m.put("cc", entity.getRecipientCc() != null ? java.util.Arrays.asList(entity.getRecipientCc().split(",\\s*")) : new ArrayList<>());
            m.put("bcc", new java.util.ArrayList<>());
            m.put("attachments", new java.util.ArrayList<>()); // Placeholder for legacy compat
            m.put("hasAttachments", entity.isHasAttachments());
            m.put("hasCloudLinks", false); // Default for legacy
            m.put("hasPhysicalAttachments", entity.isHasAttachments());
            
            String dateStr;
            if (entity.getReceivedDate() != null) {
                dateStr = entity.getReceivedDate().atZone(ZoneId.systemDefault()).toInstant().toString();
            } else {
                dateStr = "2024-01-01T00:00:00Z";
            }
            m.put("receivedAt", dateStr);
            m.put("createdAt", dateStr);
            m.put("summary", entity.getSummary());
            m.put("status", entity.getStatus());
            m.put("mailboxId", entity.getStatus() != null ? entity.getStatus() : "INBOX");
            m.put("deletedAt", entity.getDeletedAt() != null ? entity.getDeletedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null);
            m.put("previousStatus", entity.getPreviousStatus());
            
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
        
        // Universal "You" recognition for Kanban
        String senderEmail = email.getSender();
        if (senderEmail != null && senderEmail.contains("<")) {
            senderEmail = senderEmail.substring(senderEmail.indexOf("<") + 1, senderEmail.indexOf(">")).trim();
        }
        boolean isFromMe = false;
        if (activeAccount != null && senderEmail != null && senderEmail.equalsIgnoreCase(activeAccount.getEmailAddress())) {
            isFromMe = true;
        }
        card.put("is_from_me", isFromMe);

        card.put("subject", email.getSubject());
        card.put("summary", email.getSummary());
        card.put("summary_source", email.getSummarySource() != null ? email.getSummarySource().name() : null);
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
