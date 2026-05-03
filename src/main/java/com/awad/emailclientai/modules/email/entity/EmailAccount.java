package com.awad.emailclientai.modules.email.entity;

import com.awad.emailclientai.modules.user.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;




/**
 * Entity representing a linked email account for a user.
 * This stores the IMAP/SMTP connection details needed to access external mail servers.
 * Credentials (password or OAuth tokens) are stored encrypted.
 */
@Entity
@Table(name = "email_accounts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "email_address"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user who owns this linked email account.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    /**
     * The email address of the linked account (e.g., user@gmail.com).
     */
    @Column(name = "email_address", nullable = false)
    private String emailAddress;

    /**
     * Display name for this account (e.g., "Work Gmail", "Personal Outlook").
     */
    @Column(name = "display_name")
    private String displayName;

    /**
     * The email provider type.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailProvider provider;

    /**
     * The authentication type used for this account.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private EmailAuthType authType;

    // ========== IMAP Configuration ==========
    @Column(name = "imap_host", nullable = false)
    private String imapHost;

    @Column(name = "imap_port", nullable = false)
    private Integer imapPort;

    @Column(name = "imap_ssl", nullable = false)
    @Builder.Default
    private Boolean imapSsl = true;

    // ========== SMTP Configuration ==========
    @Column(name = "smtp_host", nullable = false)
    private String smtpHost;

    @Column(name = "smtp_port", nullable = false)
    private Integer smtpPort;

    @Column(name = "smtp_starttls", nullable = false)
    @Builder.Default
    private Boolean smtpStartTls = true;

    // ========== Credentials (Encrypted) ==========
    /**
     * Username for IMAP/SMTP login. Usually the email address itself.
     */
    @Column(nullable = false)
    private String username;

    /**
     * Encrypted password for BASIC auth, or encrypted access token for OAUTH2.
     * This is AES-256 encrypted before storage.
     */
    @Column(name = "encrypted_password", columnDefinition = "TEXT")
    @JsonIgnore
    private String encryptedPassword;

    /**
     * Encrypted OAuth2 refresh token (for OAUTH2 auth type).
     * Used to get new access tokens when they expire.
     */
    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    @JsonIgnore
    private String encryptedRefreshToken;

    // ========== Status & Metadata ==========
    /**
     * Whether this account is currently active/enabled.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Last time emails were successfully synced from this account.
     */
    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    /**
     * Last error message if connection failed.
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== Gmail Watch Metadata ==========
    /**
     * Expiration time for the Gmail watch registration.
     */
    @Column(name = "watch_expiration")
    private LocalDateTime watchExpiration;

    /**
     * History ID from the last watch registration or notification.
     */
    @Column(name = "watch_history_id")
    private Long watchHistoryId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}