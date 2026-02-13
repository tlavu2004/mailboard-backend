package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.dto.response.SearchResultDto;
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
    private final com.awad.emailclientai.modules.email.service.AiService aiService;

    @PostMapping("/{id}/summarize")
    @Operation(summary = "Summarize email content", description = "Generates a summary using AI or local fallback.")
    public ResponseEntity<ApiResponse<String>> summarizeEmail(@PathVariable Long id) {
        String summary = aiService.summarizeEmail(id);
        // "Summary generated" is the message, summary is the data.
        // This avoids collision with ApiResponse.success(String message)
        return ResponseEntity.ok(ApiResponse.success("Summary generated", summary));
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync emails using IMAP", description = "Fetches recent emails from IMAP and saves them to the DB.")
    public ResponseEntity<ApiResponse<String>> syncEmails(
            @RequestParam Long accountId,
            @RequestParam(defaultValue = "INBOX") String folderName,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int page) {
        emailSyncService.syncEmailsForAccount(accountId, folderName, limit, page);
        return ResponseEntity.ok(ApiResponse.success("Sync completed"));
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
                            .uid(row[2] != null ? ((Number) row[2]).longValue() : null)
                            .subject((String) row[3])
                            .sender((String) row[4])
                            .snippet((String) row[5])
                            .body((String) row[6])
                            .status(row[7] != null ? EmailStatus.valueOf((String) row[7]) : null)
                            .receivedDate(row[8] != null ? ((java.sql.Timestamp) row[8]).toLocalDateTime() : null)
                            .snoozedUntil(row[9] != null ? ((java.sql.Timestamp) row[9]).toLocalDateTime() : null)
                            .summary((String) row[10])
                            .isRead(row[11] != null && (Boolean) row[11])
                            .hasAttachments(row[12] != null && (Boolean) row[12])
                            .gmailLink(String.format("https://mail.google.com/mail/u/0/#search/rfc822msgid:%s", 
                                    java.net.URLEncoder.encode((String) row[1], java.nio.charset.StandardCharsets.UTF_8)))
                            .build();
                    double score = row[14] != null ? ((Number) row[14]).doubleValue() : 0.0;
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
            @RequestParam(required = false) EmailStatus status,
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
                .body(entity.getBody())
                .status(entity.getStatus())
                .receivedDate(entity.getReceivedDate())
                .snoozedUntil(entity.getSnoozedUntil())
                .summary(entity.getSummary())
                .isRead(entity.isRead())
                .hasAttachments(entity.isHasAttachments())
                .gmailLink(String.format("https://mail.google.com/mail/u/0/#search/rfc822msgid:%s", 
                        java.net.URLEncoder.encode(entity.getMessageId(), java.nio.charset.StandardCharsets.UTF_8)))
                .build();
    }
}
