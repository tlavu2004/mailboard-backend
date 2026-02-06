package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emails")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Emails (Kanban)", description = "Manage persisted emails for Kanban workflow")
public class EmailController {

    private final EmailRepository emailRepository;
    private final EmailSyncService emailSyncService;

    @PostMapping("/sync")
    @Operation(summary = "Sync emails using IMAP", description = "Fetches recent emails from IMAP and saves them to the DB.")
    public ResponseEntity<ApiResponse<String>> syncEmails(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "INBOX") String folderName,
            @RequestParam(defaultValue = "50") int limit) {
        emailSyncService.syncEmailsForAccount(accountId, folderName, limit);
        return ResponseEntity.ok(ApiResponse.success("Sync completed"));
    }

    @GetMapping
    @Operation(summary = "Get emails by status", description = "Retrieve emails for Kanban columns.")
    public ResponseEntity<ApiResponse<List<EmailEntityDto>>> getEmails(
            @RequestParam Long accountId,
            @RequestParam(required = false) EmailStatus status) {
        
        List<EmailEntity> entities;
        if (status != null) {
            // simplified for MVP: filter by status only (ideally also accountId)
            entities = emailRepository.findByStatus(status).stream()
                    .filter(e -> e.getAccount().getId().equals(accountId))
                    .collect(Collectors.toList());
        } else {
            entities = emailRepository.findByAccountId(accountId);
        }

        List<EmailEntityDto> dtos = entities.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Update email status", description = "Move card between columns (e.g., INBOX -> DONE).")
    public ResponseEntity<ApiResponse<EmailEntityDto>> updateStatus(
            @PathVariable Long id,
            @RequestParam EmailStatus status) {
        
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        email.setStatus(status);
        
        // If moving out of snoozed, clear the date
        if (status != EmailStatus.SNOOZED) {
            email.setSnoozedUntil(null);
        }
        
        EmailEntity saved = emailRepository.save(email);
        return ResponseEntity.ok(ApiResponse.success(mapToDto(saved)));
    }

    @PutMapping("/{id}/snooze")
    @Operation(summary = "Snooze email", description = "Move to SNOOZED status until a specific time.")
    public ResponseEntity<ApiResponse<EmailEntityDto>> snoozeEmail(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until) {
        
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        email.setStatus(EmailStatus.SNOOZED);
        email.setSnoozedUntil(until);
        
        EmailEntity saved = emailRepository.save(email);
        return ResponseEntity.ok(ApiResponse.success(mapToDto(saved)));
    }

    private EmailEntityDto mapToDto(EmailEntity entity) {
        return EmailEntityDto.builder()
                .id(entity.getId())
                .messageId(entity.getMessageId())
                .uid(entity.getUid())
                .subject(entity.getSubject())
                .sender(entity.getSender())
                .snippet(entity.getSnippet())
                .status(entity.getStatus())
                .receivedDate(entity.getReceivedDate())
                .snoozedUntil(entity.getSnoozedUntil())
                .build();
    }
}
