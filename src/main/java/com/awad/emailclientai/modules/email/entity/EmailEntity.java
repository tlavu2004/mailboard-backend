package com.awad.emailclientai.modules.email.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private String messageId; 

    private String threadId; 
    private String gmailMessageId; 
    private String gmailDraftId; 

    private Long uid; 

    private String subject;

    private String sender;

    private String fromName;

    @Column(columnDefinition = "TEXT")
    private String recipientTo;

    @Column(columnDefinition = "TEXT")
    private String recipientCc;

    @Column(length = 500)
    private String snippet; 

    @Column(columnDefinition = "TEXT")
    private String body;

    @Builder.Default
    private String status = EmailStatus.INBOX;

    private String previousStatus; // NEW: Store status before moving to trash
    private LocalDateTime deletedAt; // NEW: Timestamp when moved to trash

    private LocalDateTime receivedDate;

    private OffsetDateTime snoozedUntil;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    private SummarySource summarySource;

    @Builder.Default
    private boolean isRead = false;

    @Builder.Default
    private boolean isStarred = false;

    @Builder.Default
    private boolean hasAttachments = false;

    private Double kanbanOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private EmailAccount account;

    @Column(name = "embedding_768", columnDefinition = "vector", insertable = false, updatable = false)
    private String embedding768;

    @Column(name = "embedding_384", columnDefinition = "vector", insertable = false, updatable = false)
    private String embedding384;
    
    @OneToMany(mappedBy = "email", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<EmailAttachment> attachments = new ArrayList<>();
}
