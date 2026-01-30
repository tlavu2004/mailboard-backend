package com.awad.emailclientai.modules.email.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EmailPreviewResponse {
    private Long id;
    private String sender;
    private String subject;
    private String preview;
    private LocalDateTime sentAt;
    private boolean isRead;
    private boolean isStarred;
}
