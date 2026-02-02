package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an email folder/mailbox from IMAP.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailFolderDto {
    
    /**
     * Full name of the folder (e.g., "INBOX", "[Gmail]/Sent Mail").
     */
    private String name;
    
    /**
     * Display name (cleaned up for UI).
     */
    private String displayName;
    
    /**
     * Total number of messages in the folder.
     */
    private int messageCount;
    
    /**
     * Number of unread messages.
     */
    private int unreadCount;
    
    /**
     * Folder type for UI categorization.
     */
    private String type; // INBOX, SENT, DRAFTS, TRASH, SPAM, CUSTOM
}
