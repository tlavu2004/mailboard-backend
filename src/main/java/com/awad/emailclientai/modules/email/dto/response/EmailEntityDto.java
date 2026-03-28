package com.awad.emailclientai.modules.email.dto.response;


import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
    private LocalDateTime snoozedUntil;
    private String summary;
    private boolean isRead;
    private boolean hasAttachments;
    private String gmailLink;
    private String accountEmail;
}
