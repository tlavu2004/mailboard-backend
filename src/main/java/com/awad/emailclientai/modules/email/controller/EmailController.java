package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.dto.response.SearchResultDto;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.modules.email.service.ImapService;
import com.awad.emailclientai.modules.kanban.repository.KanbanColumnRepository;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emails")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Emails (Kanban)", description = "Manage persisted emails for Kanban workflow")
public class EmailController {

    private final EmailRepository emailRepository;
    private final ImapService imapService;
    private final KanbanColumnRepository kanbanColumnRepository;
    private final EmailSyncService emailSyncService;
    private final com.awad.emailclientai.modules.email.service.AiService aiService;

    @PostMapping("/{id}/summarize")
    @Operation(summary = "Generate AI Email Summary", description = "Generates a summary using AI or local fallback.")
    public ResponseEntity<ApiResponse<String>> summarizeEmail(@PathVariable Long id) {
        String summary = aiService.summarizeEmail(id);
        // "Summary generated" is the message, summary is the data.
        // This avoids collision with ApiResponse.success(String message)
        return ResponseEntity.ok(ApiResponse.success("Summary generated", summary));
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync Emails from Gmail", description = "Fetches recent emails from IMAP and saves them to the DB.")
    public ResponseEntity<ApiResponse<String>> syncEmails(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "INBOX") String folderName,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int page) {
        
        if (accountId != null) {
            emailSyncService.syncEmailsForAccount(accountId, principal.getId(), folderName, limit, page);
        } else {
            emailSyncService.syncEmailsForUser(principal.getId(), folderName, limit, page);
        }
        
        return ResponseEntity.ok(ApiResponse.success("Sync completed"));
    }

    @PostMapping("/repair")
    @Operation(summary = "Repair Corrupted Email Bodies", description = "Scans for emails with corrupted bodies and re-syncs them from Gmail.")
    public ResponseEntity<ApiResponse<String>> repairEmails(
            @AuthenticationPrincipal UserPrincipal principal) {
        
        emailSyncService.repairEmailsForUser(principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Repair process completed"));
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "Force Refresh Email Content", description = "Re-fetches the full email content from Gmail for a specific email ID.")
    public ResponseEntity<ApiResponse<String>> refreshEmail(@PathVariable Long id) {
        emailSyncService.refreshEmail(id);
        return ResponseEntity.ok(ApiResponse.success("Email refreshed successfully"));
    }

    @GetMapping("/search")
    @Operation(summary = "Fuzzy Search Emails with Relevance Ranking", description = "Fuzzy search by subject or sender with relevance ranking.")
    public ResponseEntity<ApiResponse<List<SearchResultDto>>> searchEmails(
            @RequestParam Long accountId,
            @RequestParam String q) {
        
        List<Object[]> rows = emailRepository.searchEmailsWithScore(accountId, q);
        List<SearchResultDto> results = rows.stream()
                .map(row -> {
                    EmailEntityDto emailDto = EmailEntityDto.builder()
                            .id(((Number) row[0]).longValue())
                            .messageId((String) row[1])
                            .threadId((String) row[2])
                            .gmailMessageId((String) row[3])
                            .uid(row[4] != null ? ((Number) row[4]).longValue() : null)
                            .subject((String) row[5])
                            .sender((String) row[6])
                            .snippet((String) row[7])
                            .body((String) row[8])
                            .status((String) row[9])
                            .receivedDate(row[10] != null ? ((java.sql.Timestamp) row[10]).toLocalDateTime() : null)
                            .snoozedUntil(row[11] != null ? ((java.sql.Timestamp) row[11]).toInstant().atOffset(ZoneOffset.UTC) : null)
                            .summary((String) row[12])
                            .summarySource(row.length > 18 ? (String) row[18] : null)
                            .isRead(row[13] != null && (Boolean) row[13])
                            .hasAttachments(row[14] != null && (Boolean) row[14])
                            .accountEmail((String) row[15])
                            .gmailLink((String) row[3] != null ? 
                                    String.format("https://mail.google.com/mail/u/%s/#inbox/%s", 
                                        URLEncoder.encode((String) row[15], StandardCharsets.UTF_8), (String) row[3]) :
                                    String.format("https://mail.google.com/mail/u/%s/#search/rfc822msgid:%s", 
                                        URLEncoder.encode((String) row[15], StandardCharsets.UTF_8),
                                        URLEncoder.encode((String) row[1], StandardCharsets.UTF_8)))
                            .build();
                    double score = row[17] != null ? ((Number) row[17]).doubleValue() : 0.0;
                    return SearchResultDto.builder()
                            .email(emailDto)
                            .relevanceScore(Math.round(score * 100.0) / 100.0)
                            .build();
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @GetMapping
    @Operation(summary = "List and Filter Emails", description = "Retrieve emails for Kanban columns with filtering and sorting.")
    public ResponseEntity<ApiResponse<List<EmailEntityDto>>> getEmails(
            @RequestParam Long accountId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean hasAttachments,
            @RequestParam(defaultValue = "receivedDate,desc") String sort) {
        
        // Parse sort parameter (simple implementation: "field,direction")
        String[] sortParts = sort.split(",");
        String sortField = sortParts[0];
        org.springframework.data.domain.Sort.Direction direction = org.springframework.data.domain.Sort.Direction.DESC;
        if (sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1])) {
            direction = org.springframework.data.domain.Sort.Direction.ASC;
        }
        org.springframework.data.domain.Sort sortObj = org.springframework.data.domain.Sort.by(direction, sortField);

        org.springframework.data.jpa.domain.Specification<EmailEntity> spec = 
                com.awad.emailclientai.modules.email.repository.EmailSpecification.filterEmails(accountId, status, unread, hasAttachments);

        List<EmailEntity> entities = emailRepository.findAll(spec, sortObj);

        List<EmailEntityDto> dtos = entities.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Update Email Task Status", description = "Move card between columns (e.g., INBOX -> DONE). Also syncs Gmail labels if mapped.")
    public ResponseEntity<ApiResponse<EmailEntityDto>> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Email not found"));
        
        String oldStatus = email.getStatus();
        email.setStatus(status);
        
        // If moving out of snoozed, clear the date
        if (!EmailStatus.SNOOZED.equals(status)) {
            email.setSnoozedUntil(null);
        }
        
        EmailEntity saved = emailRepository.save(email);

        try {
            // Look up old label to remove
            String oldLabelId = null;
            if (oldStatus != null) {
                oldLabelId = kanbanColumnRepository
                        .findByAccountIdAndLinkedStatus(email.getAccount().getId(), oldStatus)
                        .map(col -> col.getGmailLabelId())
                        .filter(label -> label != null && !label.isBlank())
                        .orElse(null);
            }

            // Look up new label to add
            String finalOldLabelId = oldLabelId;
            kanbanColumnRepository.findByAccountIdAndLinkedStatus(email.getAccount().getId(), status)
                .ifPresent(column -> {
                    if (column.getGmailLabelId() != null && !column.getGmailLabelId().isBlank()) {
                        try {
                            imapService.syncLabel(email.getAccount(), "INBOX", email.getUid(), 
                                    finalOldLabelId, column.getGmailLabelId());
                        } catch (Exception e) {
                            log.error("Failed to sync Gmail label: {}", e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            log.warn("Failed to find kanban column mapping: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(mapToDto(saved)));
    }

    @PutMapping("/{id}/snooze")
    @Operation(summary = "Snooze Email to Future", description = "Move to SNOOZED status until a specific time.")
    public ResponseEntity<ApiResponse<EmailEntityDto>> snoozeEmail(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until) {
        
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
                .body(entity.getBody())
                .status(entity.getStatus())
                .receivedDate(entity.getReceivedDate())
                .snoozedUntil(entity.getSnoozedUntil())
                .summary(entity.getSummary())
                .summarySource(entity.getSummarySource() != null ? entity.getSummarySource().name() : null)
                .isRead(entity.isRead())
                .hasAttachments(entity.isHasAttachments())
                .accountEmail(entity.getAccount().getEmailAddress())
                .gmailLink(entity.getGmailMessageId() != null ? 
                        String.format("https://mail.google.com/mail/u/%s/#inbox/%s", 
                            URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                            entity.getGmailMessageId()) :
                        String.format("https://mail.google.com/mail/u/%s/#search/rfc822msgid:%s", 
                            URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                            URLEncoder.encode(entity.getMessageId(), StandardCharsets.UTF_8)))
                .build();
    }
}
