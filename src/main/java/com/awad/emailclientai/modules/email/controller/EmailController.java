package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.EmailDetailResponse;
import com.awad.emailclientai.modules.email.dto.EmailPreviewResponse;
import com.awad.emailclientai.modules.email.dto.MailboxResponse;
import com.awad.emailclientai.modules.email.entity.Mailbox;
import com.awad.emailclientai.modules.email.service.EmailService;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    @GetMapping("/mailboxes")
    public ResponseEntity<ApiResponse<List<MailboxResponse>>> getMailboxes(Authentication authentication) {
        String email = authentication.getName();
        List<Mailbox> mailboxes = emailService.getMailboxes(email);

        List<MailboxResponse> response = mailboxes.stream()
                .map(mb -> MailboxResponse.builder()
                        .id(mb.getId())
                        .name(mb.getName())
                        .type(mb.getType())
                        .unreadCount(0) // Unread count logic to be implemented
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/mailboxes/seed")
    public ResponseEntity<ApiResponse<String>> seedData(Authentication authentication) {
        String email = authentication.getName();
        emailService.seedDummyData(email);
        return ResponseEntity.ok(ApiResponse.success("Dummy data seeded successfully"));
    }

    @GetMapping("/mailboxes/{mailboxId}/emails")
    public ResponseEntity<ApiResponse<Page<EmailPreviewResponse>>> getEmails(
            @PathVariable Long mailboxId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(emailService.getEmailsByMailbox(mailboxId, pageable)));
    }

    @GetMapping("/emails/{emailId}")
    public ResponseEntity<ApiResponse<EmailDetailResponse>> getEmailDetail(@PathVariable Long emailId) {
        return ResponseEntity.ok(ApiResponse.success(emailService.getEmailById(emailId)));
    }
}
