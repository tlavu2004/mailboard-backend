package com.awad.emailclientai.modules.email.dto.request;

import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for connecting/linking a new email account.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectEmailAccountRequestDto {

    /**
     * The email address to connect.
     */
    @NotBlank(message = "Email address is required")
    @Email(message = "Invalid email format")
    private String emailAddress;

    /**
     * Display name for this account (optional).
     */
    private String displayName;

    /**
     * Email provider type. If set to known provider (GMAIL, OUTLOOK, etc.),
     * default IMAP/SMTP settings will be used.
     */
    @NotNull(message = "Provider is required")
    private EmailProvider provider;

    /**
     * Authentication type.
     */
    @NotNull(message = "Auth type is required")
    private EmailAuthType authType;

    // ========== IMAP Configuration (optional if using known provider) ==========
    private String imapHost;
    private Integer imapPort;
    private Boolean imapSsl;

    // ========== SMTP Configuration (optional if using known provider) ==========
    private String smtpHost;
    private Integer smtpPort;
    private Boolean smtpStartTls;

    // ========== Credentials ==========
    /**
     * Username for IMAP/SMTP login (optional, defaults to email address).
     */
    private String username;

    /**
     * Password or App Password for  auth.
     * For OAUTH2, this is the access token.
     */
    @NotBlank(message = "Password is required")
    private String password;

    /**
     * OAuth2 refresh token (only for OAUTH2 auth type).
     */
    private String refreshToken;
}
