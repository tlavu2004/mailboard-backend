package com.awad.emailclientai.modules.email.dto.response;

import com.awad.emailclientai.modules.email.entity.EmailStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EmailEntityDto {
    private Long id;
    private String messageId;
    private Long uid;
    private String subject;
    private String sender;
    private String snippet;
    private String body;
    private EmailStatus status;
    private LocalDateTime receivedDate;
    private LocalDateTime snoozedUntil;
    private String summary;
    private boolean isRead;
    private boolean hasAttachments;
}
