package com.awad.emailclientai.modules.email.entity;

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

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EmailStatus status = EmailStatus.INBOX;

    private LocalDateTime receivedDate;

    private LocalDateTime snoozedUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private EmailAccount account;
}
