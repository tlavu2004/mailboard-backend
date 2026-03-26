package com.awad.emailclientai.modules.email.controller;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.service.AiService;
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
        
        List<EmailEntity> emails = emailRepository.findByAccountId(account.getId());
        String finalStatus = status;
        List<Map<String, Object>> filtered = emails.stream()
                .filter(e -> finalStatus.equals("INBOX") || finalStatus.equals(e.getStatus()))
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
        List<EmailEntity> emails = emailRepository.findByAccountId(account.getId());
        
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
            @RequestBody Map<String, String> request
    ) {
        Long emailId = Long.parseLong(request.get("email_id"));
        String toStatus = request.get("to_status");
        
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        email.setStatus(toStatus);
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
            email.setStatus("STARRED");
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
            m.put("threadId", entity.getMessageId()); // Default to messageId
            m.put("mailboxId", entity.getStatus() != null ? entity.getStatus() : "INBOX");
            
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
            m.put("isStarred", "STARRED".equalsIgnoreCase(entity.getStatus()));
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
        card.put("sender", email.getSender());
        card.put("subject", email.getSubject());
        card.put("summary", email.getSummary());
        card.put("preview", email.getSnippet());
        card.put("gmail_url", String.format("https://mail.google.com/mail/u/0/#search/rfc822msgid:%s", email.getMessageId()));
        card.put("received_at", email.getReceivedDate() != null ? email.getReceivedDate().toString() : "");
        card.put("is_read", email.isRead());
        card.put("has_attachments", email.isHasAttachments());
        return card;
    }
}
