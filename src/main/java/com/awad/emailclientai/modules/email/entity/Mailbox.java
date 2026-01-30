package com.awad.emailclientai.modules.email.entity;

import com.awad.emailclientai.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "mailboxes", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "name" })
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mailbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // Inbox, Sent, Trash, etc.

    @Column(nullable = false)
    private String type; // SYSTEM or CUSTOM

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "mailbox", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Email> emails = new ArrayList<>();
}
