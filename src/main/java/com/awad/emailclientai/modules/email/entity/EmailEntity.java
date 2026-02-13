package com.awad.emailclientai.modules.email.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "emails")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String messageId; // Unique Message-ID header

    private Long uid; // IMAP UID

    private String subject;

    private String sender;

    @Column(length = 500)
    private String snippet; // Short preview

    @Column(columnDefinition = "TEXT")
    private String body;

    @Builder.Default
    private String status = EmailStatus.INBOX;

    private LocalDateTime receivedDate;

    private LocalDateTime snoozedUntil;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Builder.Default
    private boolean isRead = false;

    @Builder.Default
    private boolean hasAttachments = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private EmailAccount account;

    @Column(name = "embedding_768", columnDefinition = "vector", insertable = false, updatable = false)
    private String embedding768;

    @Column(name = "embedding_384", columnDefinition = "vector", insertable = false, updatable = false)
    private String embedding384;
}
