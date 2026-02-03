package com.awad.emailclientai.modules.email.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO representing full email details including body and attachments.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailMessageDetailDto {
    
    private long uid;
    private String messageId;
    private String from;
    private String fromName;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private String replyTo;
    private String subject;
    private LocalDateTime sentAt;
    private LocalDateTime receivedAt;
    private boolean read;
    private boolean starred;
    
    /**
     * Plain text body.
     */
    private String bodyText;
    
    /**
     * HTML body.
     */
    private String bodyHtml;
    
    /**
     * List of attachments.
     */
    private List<AttachmentDto> attachments;
    
    /**
     * In-Reply-To header (for threading).
     */
    private String inReplyTo;
    
    /**
     * References header (for threading).
     */
    private List<String> references;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttachmentDto {
        private String id;
        private String filename;
        private String contentType;
        private long size;
        private boolean inline;
        private String contentId; // For inline images
    }
}
