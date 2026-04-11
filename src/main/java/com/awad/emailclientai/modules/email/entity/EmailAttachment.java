package com.awad.emailclientai.modules.email.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private EmailEntity email;

    @Column(nullable = false)
    private String filename;

    private String contentType;

    private long size;

    /**
     * The ID used by the mail server (e.g., IMAP attachment index or Gmail attachmentId)
     */
    private String serverAttachmentId;

    /**
     * Content-ID used for inline images (e.g., <img src="cid:abc">)
     */
    private String contentId;

    @Builder.Default
    private boolean inline = false;
}
