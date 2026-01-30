package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.Email;
import com.awad.emailclientai.modules.email.entity.Mailbox;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.repository.MailboxRepository;
import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final MailboxRepository mailboxRepository;
    private final EmailRepository emailRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.awad.emailclientai.modules.email.dto.EmailPreviewResponse> getEmailsByMailbox(Long mailboxId, org.springframework.data.domain.Pageable pageable) {
        return emailRepository.findByMailboxId(mailboxId, pageable)
                .map(email -> com.awad.emailclientai.modules.email.dto.EmailPreviewResponse.builder()
                        .id(email.getId())
                        .sender(email.getSender())
                        .subject(email.getSubject())
                        .preview(email.getPreview())
                        .sentAt(email.getSentAt())
                        .isRead(email.isRead())
                        .isStarred(email.isStarred())
                        .build());
    }

    @Transactional(readOnly = true)
    public com.awad.emailclientai.modules.email.dto.EmailDetailResponse getEmailById(Long emailId) {
        Email email = emailRepository.findById(emailId)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        return com.awad.emailclientai.modules.email.dto.EmailDetailResponse.builder()
                .id(email.getId())
                .sender(email.getSender())
                .recipient(email.getRecipient())
                .subject(email.getSubject())
                .body(email.getBody())
                .sentAt(email.getSentAt())
                .isRead(email.isRead())
                .isStarred(email.isStarred())
                .build();
    }

    @Transactional(readOnly = true)
    public List<Mailbox> getMailboxes(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return mailboxRepository.findByUserId(user.getId());
    }

    @Transactional
    public void seedDummyData(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Mailbox inbox = createMailbox(user, "Inbox", "SYSTEM");
        Mailbox sent = createMailbox(user, "Sent", "SYSTEM");
        createMailbox(user, "Trash", "SYSTEM");
        createMailbox(user, "Drafts", "SYSTEM");
        createMailbox(user, "Starred", "SYSTEM");

        createEmail(inbox, "boss@company.com", userEmail, "Meeting Update", "Meeting moved to 3 PM", true, false);
        createEmail(inbox, "newsletter@tech.com", userEmail, "Weekly Tech News", "Here is the latest in tech...", false, false);
        createEmail(sent, userEmail, "client@gmail.com", "Project Proposal", "Attached is the proposal...", true, true);
    }

    private Mailbox createMailbox(User user, String name, String type) {
        return mailboxRepository.save(Mailbox.builder()
                .user(user)
                .name(name)
                .type(type)
                .build());
    }

    private void createEmail(Mailbox mailbox, String from, String to, String subject, String body, boolean isRead, boolean isStarred) {
        emailRepository.save(Email.builder()
                .mailbox(mailbox)
                .sender(from)
                .recipient(to)
                .subject(subject)
                .body(body)
                .preview(body.length() > 50 ? body.substring(0, 50) + "..." : body)
                .sentAt(LocalDateTime.now())
                .isRead(isRead)
                .isStarred(isStarred)
                .build());
    }
}
