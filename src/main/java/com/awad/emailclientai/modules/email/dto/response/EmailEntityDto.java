package com.awad.emailclientai.modules.email.dto.response;


import lombok.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailEntityDto {
    private Long id;
    private String messageId;
    private String threadId;
    private String gmailMessageId;
    private Long uid;
    private String subject;
    private EmailAddressDto from;
    private List<EmailAddressDto> to;
    private List<EmailAddressDto> cc;
    private String sender; // Legacy fallback
    private String fromName; // Legacy fallback
    private List<String> recipientTo; // Legacy fallback
    private List<String> recipientCc; // Legacy fallback
    private String snippet;
    private String body;
    private String status;
    private String mailboxId;
    private LocalDateTime receivedDate;
    private String receivedAt;
    private OffsetDateTime snoozedUntil;
    private String summary;
    private String summarySource;
    private boolean isRead;
    private boolean isStarred;
    private boolean hasAttachments;
    private boolean hasCloudLinks; // V10.35
    private boolean hasPhysicalAttachments; // V10.35
    private String gmailLink;
    private String accountEmail;
    private List<AttachmentDto> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmailAddressDto {
        private String name;
        private String email;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentDto {
        private String id;
        private String filename;
        private long size;
        private String contentType;
        private String serverAttachmentId;
        private String contentId;
        private boolean inline;
        private String url;
        private String externalUrl;
    }
}
