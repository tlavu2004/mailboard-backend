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
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
            unreadCount = (int) emailRepository.countUnreadByAccountId(account.getId());
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
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/mailboxes/{id}/emails")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEmailsByMailbox(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        
        // Map mailbox ID to status (lowercase)
        String status = id.toUpperCase();
        
        List<EmailEntity> emails = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId());
        String finalStatus = status;
        List<Map<String, Object>> filtered = emails.stream()
                .filter(e -> {
                    if ("STARRED".equalsIgnoreCase(finalStatus)) {
                        return e.isStarred();
                    }
                    return finalStatus.equalsIgnoreCase("INBOX") || finalStatus.equalsIgnoreCase(e.getStatus());
                })
                .map(this::mapToFrontendEmail)
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("emails", filtered);
        data.put("total", filtered.size());
        data.put("page", page);
        data.put("perPage", perPage);
        data.put("hasNextPage", false);
        
        log.info("Bridge: Returning {} emails for mailbox {}", filtered.size(), id);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/kanban")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKanban(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        EmailAccount account = getPrimaryAccount(principal);
        List<EmailEntity> emails = emailRepository.findAllByAccountIdOrderByKanbanOrderDescReceivedDateDesc(account.getId());
        
        Map<String, List<Map<String, Object>>> columnsData = new HashMap<>();
        
        // Initialize columns
        columnsData.put("INBOX", new ArrayList<>());
        columnsData.put("TODO", new ArrayList<>());
        columnsData.put("DOING", new ArrayList<>());
        columnsData.put("DONE", new ArrayList<>());
        columnsData.put("SNOOZED", new ArrayList<>());

        for (EmailEntity email : emails) {
            String status = email.getStatus() != null ? email.getStatus().toUpperCase() : "INBOX";
            if (!columnsData.containsKey(status)) {
                columnsData.put(status, new ArrayList<>());
            }
            columnsData.get(status).add(mapToKanbanCard(email));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columnsData);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/kanban/move")
    public ResponseEntity<ApiResponse<Void>> moveCard(
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
        
        return ResponseEntity.ok(ApiResponse.success("Card moved"));
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

    @GetMapping("/kanban/meta")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKanbanMeta(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<Map<String, String>> columns = new ArrayList<>();
        columns.add(createColMeta("INBOX", "Inbox", "#667eea"));
        columns.add(createColMeta("TODO", "To Do", "#f6ad55"));
        columns.add(createColMeta("DOING", "Doing", "#4299e1"));
        columns.add(createColMeta("DONE", "Done", "#48bb78"));
        columns.add(createColMeta("SNOOZED", "Snoozed", "#a0aec0"));

        Map<String, Object> data = new HashMap<>();
        data.put("columns", columns);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/emails/{id}/modify")
    public ResponseEntity<ApiResponse<Void>> modifyEmail(
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
        
        return ResponseEntity.ok(ApiResponse.success("Modify completed"));
    }

    @GetMapping("/emails/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEmailDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id
    ) {
        Long emailId = Long.parseLong(id);
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        return ResponseEntity.ok(ApiResponse.success(mapToFrontendEmail(email)));
    }

    @GetMapping("/gmail/labels")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGmailLabels(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<Map<String, String>> labels = new ArrayList<>();
        labels.add(Map.of("id", "INBOX", "name", "Inbox", "type", "system"));
        labels.add(Map.of("id", "SENT", "name", "Sent", "type", "system"));
        labels.add(Map.of("id", "DRAFTS", "name", "Drafts", "type", "system"));
        
        Map<String, Object> data = new HashMap<>();
        data.put("labels", labels);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/search/generate-embeddings")
    @Operation(summary = "Legacy Embedding Generator", description = "Dummy endpoint for frontend periodic task.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateEmbeddings() {
        Map<String, Object> result = new HashMap<>();
        result.put("processed", 0);
        result.put("failed", 0);
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

    private Map<String, String> createColMeta(String key, String label, String color) {
        Map<String, String> m = new HashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("color", color);
        return m;
    }

    private Map<String, Object> mapToFrontendEmail(EmailEntity entity) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("id", entity.getId().toString());
            m.put("messageId", entity.getMessageId());
            m.put("threadId", entity.getThreadId() != null ? entity.getThreadId() : entity.getMessageId());
            m.put("gmailMessageId", entity.getGmailMessageId());
            m.put("accountEmail", entity.getAccount().getEmailAddress());
            m.put("mailboxId", entity.getStatus() != null ? entity.getStatus() : "INBOX");
            
            // Generate gmailLink
            String encodedEmail = URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8);
            String gmailLink = entity.getGmailMessageId() != null ? 
                    String.format("https://mail.google.com/mail/u/%s/#inbox/%s", encodedEmail, entity.getGmailMessageId()) :
                    String.format("https://mail.google.com/mail/u/%s/#search/rfc822msgid:%s", 
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
            
            // Required empty arrays
            m.put("to", new ArrayList<>());
            m.put("cc", new ArrayList<>());
            m.put("bcc", new ArrayList<>());
            m.put("labels", new ArrayList<>());
            m.put("attachments", new ArrayList<>());
            
            m.put("subject", entity.getSubject() != null ? entity.getSubject() : "(No Subject)");
            m.put("preview", entity.getSnippet() != null ? entity.getSnippet() : "");
            m.put("body", entity.getBody() != null ? entity.getBody() : "");
            m.put("isRead", entity.isRead());
            m.put("isStarred", entity.isStarred());
            m.put("hasAttachments", entity.isHasAttachments());
            
            String dateStr = entity.getReceivedDate() != null ? entity.getReceivedDate().toString() : "2024-01-01T00:00:00Z";
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

    private Map<String, Object> mapToKanbanCard(EmailEntity email) {
        Map<String, Object> card = new HashMap<>();
        card.put("id", email.getId().toString());
        card.put("message_id", email.getMessageId());
        card.put("gmail_message_id", email.getGmailMessageId());
        card.put("thread_id", email.getThreadId());
        card.put("account_email", email.getAccount().getEmailAddress());
        card.put("sender", email.getSender());
        card.put("subject", email.getSubject());
        card.put("summary", email.getSummary());
        card.put("preview", email.getSnippet());
        
        String encodedEmail = URLEncoder.encode(email.getAccount().getEmailAddress(), StandardCharsets.UTF_8);
        String gmailUrl = email.getGmailMessageId() != null ? 
                String.format("https://mail.google.com/mail/u/%s/#inbox/%s", encodedEmail, email.getGmailMessageId()) :
                String.format("https://mail.google.com/mail/u/%s/#search/rfc822msgid:%s", 
                    encodedEmail, URLEncoder.encode(email.getMessageId(), StandardCharsets.UTF_8));
        
        card.put("gmail_url", gmailUrl);
        card.put("received_at", email.getReceivedDate() != null ? email.getReceivedDate().toString() : "");
        card.put("is_read", email.isRead());
        card.put("is_starred", email.isStarred());
        card.put("has_attachments", email.isHasAttachments());
        return card;
    }
}
