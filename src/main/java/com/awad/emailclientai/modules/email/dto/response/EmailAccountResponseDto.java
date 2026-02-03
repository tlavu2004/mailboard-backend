package com.awad.emailclientai.modules.email.dto.response;

import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for email account information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAccountResponseDto {

    private Long id;
    private String emailAddress;
    private String displayName;
    private EmailProvider provider;
    private EmailAuthType authType;
    private String imapHost;
    private Integer imapPort;
    private String smtpHost;
    private Integer smtpPort;
    private Boolean active;
    private LocalDateTime lastSyncAt;
    private String lastError;
    private LocalDateTime createdAt;
}
