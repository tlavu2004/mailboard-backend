package com.awad.emailclientai.modules.email.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "emails")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sender; // "from" is a reserved keyword in some DBs

    @Column(nullable = false)
    private String recipient;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body; // Plain text or HTML

    @Column(columnDefinition = "TEXT")
    private String preview; // Short preview text

    private LocalDateTime sentAt; // Timestamp

    private boolean isRead;
    private boolean isStarred;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mailbox_id", nullable = false)
    private Mailbox mailbox;
}
