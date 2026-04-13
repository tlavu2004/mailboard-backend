package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO representing an email message from IMAP (list view - headers only).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MailMessageDto {
    
    /**
     * IMAP UID of the message (unique within folder).
     */
    private long uid;
    private String threadId;
    private String gmailMessageId;
    
    /**
     * Message-ID header.
     */
    private String messageId;
    
    /**
     * From address.
     */
    private String from;
    
    /**
     * From display name.
     */
    private String fromName;
    
    /**
     * To addresses.
     */
    private List<String> to;
    
    /**
     * CC addresses.
     */
    private List<String> cc;
    
    /**
     * Subject line.
     */
    private String subject;
    
    /**
     * Preview text (first ~100 chars of body).
     */
    private String preview;

    /**
     * Body content (truncated for list view).
     */
    private String body;
    
    /**
     * Date the message was sent.
     */
    private LocalDateTime sentAt;
    
    /**
     * Date the message was received.
     */
    private LocalDateTime receivedAt;
    
    /**
     * Whether the message has been read.
     */
    private boolean read;
    
    /**
     * Whether the message is starred/flagged.
     */
    private boolean starred;
    
    /**
     * Whether the message has attachments.
     */
    private boolean hasAttachments;
    
    /**
     * Gmail labels (fetched via X-GM-LABELS).
     */
    private List<String> labels;

    /**
     * Size of the message in bytes.
     */
    private int size;

    /**
     * List of attachments (metadata only).
     */
    private List<AttachmentMetadataDto> attachments;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttachmentMetadataDto {
        private String id;
        private String filename;
        private String contentType;
        private long size;
        private String contentId;
        private boolean inline;
        private String externalUrl;
    }
}
