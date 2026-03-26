package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge Controller for legacy/drifted frontend endpoints.
 * This provides immediate stability for the Dashboard while the frontend is being updated.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LegacyDashboardController {

    @GetMapping("/mailboxes")
    @Operation(summary = "Legacy Mailboxes Provider", description = "Bridge for frontend sidebar initialization.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMailboxes() {
        List<Map<String, Object>> mailboxes = new ArrayList<>();
        
        // Return standard mailboxes to satisfy frontend expectations
        mailboxes.add(createMailbox("INBOX", "Inbox", "InboxOutlined", 0, "system"));
        mailboxes.add(createMailbox("STARRED", "Starred", "StarOutlined", 0, "system"));
        mailboxes.add(createMailbox("SENT", "Sent", "SendOutlined", 0, "system"));
        mailboxes.add(createMailbox("DRAFTS", "Drafts", "FileOutlined", 0, "system"));
        mailboxes.add(createMailbox("TRASH", "Trash", "DeleteOutlined", 0, "system"));
        mailboxes.add(createMailbox("SPAM", "Spam", "FolderOutlined", 0, "system"));

        Map<String, Object> data = new HashMap<>();
        data.put("mailboxes", mailboxes);
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

    private Map<String, Object> createMailbox(String id, String name, String icon, int unread, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("unreadCount", unread);
        m.put("type", type);
        return m;
    }
}
