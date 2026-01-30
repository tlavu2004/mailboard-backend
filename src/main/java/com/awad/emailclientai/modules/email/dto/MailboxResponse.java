package com.awad.emailclientai.modules.email.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MailboxResponse {
    private Long id;
    private String name;
    private String type;
    private int unreadCount;
}
