package com.awad.emailclientai.modules.email.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EmailDetailResponse {
    private Long id;
    private String sender;
    private String recipient;
    private String subject;
    private String body;
    private LocalDateTime sentAt;
    private boolean isRead;
    private boolean isStarred;
}
