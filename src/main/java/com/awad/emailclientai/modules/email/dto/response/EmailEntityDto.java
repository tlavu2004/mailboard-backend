package com.awad.emailclientai.modules.email.dto.response;


import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@Builder
public class EmailEntityDto {
    private Long id;
    private String messageId;
    private String threadId;
    private String gmailMessageId;
    private Long uid;
    private String subject;
    private String sender;
    private String snippet;
    private String body;
    private String status;
    private LocalDateTime receivedDate;
    private String receivedAt;
    private OffsetDateTime snoozedUntil;
    private String summary;
    private String summarySource;
    private boolean isRead;
    private boolean isStarred;
    private boolean hasAttachments;
    private String gmailLink;
    private String accountEmail;
}
