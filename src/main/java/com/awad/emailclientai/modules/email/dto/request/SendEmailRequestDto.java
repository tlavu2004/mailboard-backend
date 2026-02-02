package com.awad.emailclientai.modules.email.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for composing and sending an email.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendEmailRequestDto {
    
    /**
     * Recipient email addresses (To).
     */
    private List<String> to;
    
    /**
     * CC recipients.
     */
    private List<String> cc;
    
    /**
     * BCC recipients.
     */
    private List<String> bcc;
    
    /**
     * Email subject.
     */
    private String subject;
    
    /**
     * Plain text body.
     */
    private String bodyText;
    
    /**
     * HTML body (optional, for rich text emails).
     */
    private String bodyHtml;
    
    /**
     * Message-ID of the email being replied to (for replies).
     */
    private String inReplyTo;
    
    /**
     * References chain (for threading).
     */
    private List<String> references;
    
    // Note: Attachments would be handled separately via multipart upload
}
