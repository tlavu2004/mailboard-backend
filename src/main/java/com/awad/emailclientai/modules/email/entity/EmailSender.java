package com.awad.emailclientai.modules.email.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "email_senders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSender {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    private String bestKnownName;
}
