package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.dto.response.SearchResultDto;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.modules.email.service.AiService;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.repository.EmailAttachmentRepository;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.modules.email.repository.EmailSpecification;
import com.awad.emailclientai.modules.email.entity.EmailAttachment;
import com.awad.emailclientai.modules.email.service.EmailAccountService;
import com.awad.emailclientai.modules.email.service.EmailService;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import com.awad.emailclientai.modules.email.dto.request.SendEmailRequestDto;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import org.springframework.web.multipart.MultipartFile;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import jakarta.mail.MessagingException;
import java.io.IOException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emails")
@Tag(name = "Emails (Kanban)", description = "Manage persisted emails for Kanban workflow")
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class EmailController {
    private static final Logger log = LoggerFactory.getLogger(EmailController.class);

    private final EmailRepository emailRepository;
    private final EmailAccountRepository emailAccountRepository;
    private final EmailAccountService emailAccountService;
    private final EmailSyncService emailSyncService;
    private final AiService aiService;
    private final EmailAttachmentRepository attachmentRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    public EmailController(
            EmailRepository emailRepository,
            EmailAccountRepository emailAccountRepository,
            EmailAccountService emailAccountService,
            EmailSyncService emailSyncService,
            AiService aiService,
            EmailAttachmentRepository attachmentRepository,
            EmailService emailService,
            ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.emailAccountRepository = emailAccountRepository;
        this.emailAccountService = emailAccountService;
        this.emailSyncService = emailSyncService;
        this.aiService = aiService;
        this.attachmentRepository = attachmentRepository;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/summarize")
    @Operation(summary = "Generate AI Email Summary")
    public ResponseEntity<ApiResponse<String>> summarizeEmail(@PathVariable Long id) {
        String summary = aiService.summarizeEmail(id);
        return ResponseEntity.ok(ApiResponse.success("Summary generated", summary));
    }

    @PostMapping("/{id}/force-sync")
    @Operation(summary = "Forcibly re-sync a specific email for debugging (X-RAY V10)")
    public ResponseEntity<ApiResponse<EmailEntityDto>> forceSyncEmail(@PathVariable Long id) {
        log.info("[V10-DEBUG] Force-sync requested for email ID: {}", id);
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Email not found"));
        
        try {
            emailSyncService.refreshEmail(id);
            EmailEntity updated = emailRepository.findById(id).orElse(email);
            return ResponseEntity.ok(ApiResponse.success(emailService.mapToDto(updated)));
        } catch (Exception e) {
            log.error("[V10-DEBUG] Force-sync failed for email {}: {}", id, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Force-sync failed: " + e.getMessage());
        }
    }

    @PostMapping("/sync")
    @Operation(summary = "Sync Emails from Gmail")
        public ResponseEntity<ApiResponse<String>> syncEmails(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "INBOX") String folderName,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page) {
        
        if (accountId != null) {
            emailSyncService.syncEmailsForAccount(accountId, principal.getId(), folderName, limit, page);
        } else {
            emailSyncService.syncEmailsForUser(principal.getId(), folderName, limit, page);
        }
        
        return ResponseEntity.ok(ApiResponse.success("Sync completed"));
    }

    @PostMapping("/repair")
    @Operation(summary = "Repair Corrupted Email Bodies")
        public ResponseEntity<ApiResponse<String>> repairEmails(
            @AuthenticationPrincipal UserPrincipal principal) {
        emailSyncService.repairEmailsForUser(principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Repair process completed"));
    }

    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Bridge: Send Email (JSON)")
        public ResponseEntity<ApiResponse<String>> sendEmailBridgeJson(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> jsonBody
    ) throws MessagingException {
        log.info("Bridge Send Email (JSON): Request from user {}", principal.getId());
        EmailAccount account = fetchPrimaryAccount(principal);
        SendEmailRequestDto request = mapJsonToDto(jsonBody);
        String messageId = emailAccountService.sendEmail(principal.getId(), account.getId(), request);
        // Trigger background sync for Sent folder so the new message appears in the app quickly
        final Long acctIdJson = account.getId();
        new Thread(() -> {
            try {
                String sentFolderName = "Sent";
                if (account.getProvider() == EmailProvider.GMAIL) sentFolderName = "[Gmail]/Sent Mail";
                emailSyncService.syncEmailsForAccount(acctIdJson, sentFolderName, 10, 0);
                // Detailed NEW_EMAILS notifications (with emailIds) are emitted by EmailSyncService
            } catch (Exception e) {
                log.warn("Post-send Sent sync failed for account {}: {}", acctIdJson, e.getMessage());
            }
        }).start();
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", messageId));
    }

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bridge: Send Email (Multipart)")
        public ResponseEntity<ApiResponse<String>> sendEmailBridgeMultipart(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "to", required = false) String toString,
            @RequestParam(value = "cc", required = false) String ccString,
            @RequestParam(value = "bcc", required = false) String bccString,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "threadId", required = false) String threadId
    ) throws MessagingException, IOException {
        log.info("Bridge Send Email (Multipart): Request from user {}", principal.getId());
        EmailAccount account = fetchPrimaryAccount(principal);
        SendEmailRequestDto request = new SendEmailRequestDto();

        if (toString != null) request.setTo(parseEmailList(toString, objectMapper));
        if (ccString != null) request.setCc(parseEmailList(ccString, objectMapper));
        if (bccString != null) request.setBcc(parseEmailList(bccString, objectMapper));
        request.setSubject(subject);
        request.setBodyText(body);
        request.setInReplyTo(threadId);

        String messageId;
        if (attachments != null && !attachments.isEmpty()) {
            messageId = emailAccountService.sendEmailWithAttachments(principal.getId(), account.getId(), request, attachments);
        } else {
            messageId = emailAccountService.sendEmail(principal.getId(), account.getId(), request);
        }
        // Trigger background sync for Sent folder so the new message appears in the app quickly
        final Long acctIdMulti = account.getId();
        new Thread(() -> {
            try {
                String sentFolderName = "Sent";
                if (account.getProvider() == EmailProvider.GMAIL) sentFolderName = "[Gmail]/Sent Mail";
                emailSyncService.syncEmailsForAccount(acctIdMulti, sentFolderName, 10, 0);
                // Detailed NEW_EMAILS notifications (with emailIds) are emitted by EmailSyncService
            } catch (Exception e) {
                log.warn("Post-send Sent sync failed for account {}: {}", acctIdMulti, e.getMessage());
            }
        }).start();
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", messageId));
    }

    // RENAMED from getPrimaryAccount to fetchPrimaryAccount to avoid any resolution conflicts
    private EmailAccount fetchPrimaryAccount(
            UserPrincipal principal) {
        return emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No active account linked."));
    }

    private List<String> parseEmailList(String json, ObjectMapper mapper) {
        try {
            if (json.startsWith("[")) return mapper.readValue(json, new TypeReference<List<String>>() {});
            return List.of(json);
        } catch (Exception e) {
            return List.of(json);
        }
    }

    @SuppressWarnings("unchecked")
    private SendEmailRequestDto mapJsonToDto(Map<String, Object> jsonBody) {
        SendEmailRequestDto request = new SendEmailRequestDto();
        if (jsonBody != null) {
            request.setTo((List<String>) jsonBody.get("to"));
            request.setCc((List<String>) jsonBody.get("cc"));
            request.setBcc((List<String>) jsonBody.get("bcc"));
            request.setSubject((String) jsonBody.get("subject"));
            request.setBodyText((String) jsonBody.get("body"));
            request.setInReplyTo((String) jsonBody.get("threadId"));
        }
        return request;
    }

    @PostMapping("/{id}/refresh")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<String>> refreshEmail(@PathVariable Long id) {
        emailSyncService.refreshEmail(id);
        return ResponseEntity.ok(ApiResponse.success("Email refreshed successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmailEntityDto>> getEmailDetail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(emailService.getEmailDetail(id)));
    }

    @GetMapping("/{id}/attachments/{atId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(
            @PathVariable Long id,
            @PathVariable Long atId) throws MessagingException, IOException {
        var resource = emailService.getInlineAttachment(id, atId);
        String contentType = emailService.getAttachmentContentType(atId);
        String filename = attachmentRepository.findById(atId)
            .map(EmailAttachment::getFilename)
                .orElse("attachment");
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    @GetMapping("/{id}/attachments/{atId}/inline")
    public ResponseEntity<org.springframework.core.io.Resource> getInlineAttachment(
            @PathVariable Long id,
            @PathVariable Long atId) throws MessagingException, IOException {
        var resource = emailService.getInlineAttachment(id, atId);
        String contentType = emailService.getAttachmentContentType(atId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<SearchResultDto>>> searchEmails(
            @RequestParam Long accountId,
            @RequestParam String q) {
        List<Object[]> rows = emailRepository.searchEmailsWithScore(accountId, q);
        List<SearchResultDto> results = rows.stream().map(row -> {
            Long eId = ((Number) row[0]).longValue();
            double score = row[17] != null ? ((Number) row[17]).doubleValue() : 0.0;
            
            EmailEntity emailEntity = emailRepository.findById(eId).orElse(null);
            if (emailEntity == null) return null;

            EmailEntityDto emailDto = emailService.mapToDto(emailEntity);
            return new SearchResultDto(emailDto, Math.round(score * 100.0) / 100.0);
        })
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmailEntityDto>>> getEmails(
            @RequestParam Long accountId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean hasAttachments,
            @RequestParam(defaultValue = "receivedDate,desc") String sort) {
        
        String[] sortParts = sort.split(",");
        org.springframework.data.domain.Sort.Direction direction = sortParts.length > 1 && "asc".equalsIgnoreCase(sortParts[1]) 
                ? org.springframework.data.domain.Sort.Direction.ASC : org.springframework.data.domain.Sort.Direction.DESC;
        org.springframework.data.domain.Sort sortObj = org.springframework.data.domain.Sort.by(direction, sortParts[0]);
        org.springframework.data.jpa.domain.Specification<EmailEntity> spec = 
            EmailSpecification.filterEmails(accountId, status, unread, hasAttachments);

        List<EmailEntityDto> dtos = emailRepository.findAll(spec, sortObj).stream()
                .map(emailService::mapToDto).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @PutMapping("/{id}/status")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<EmailEntityDto>> updateStatus(@PathVariable Long id, @RequestParam String status) {
        EmailEntity email = emailRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
        String previousStatus = email.getStatus();
        email.setStatus(status);
        if (!EmailStatus.SNOOZED.equals(status)) email.setSnoozedUntil(null);
        EmailEntity saved = emailRepository.save(email);
        
        emailService.syncStatusToProvider(email, previousStatus, status);
        
        return ResponseEntity.ok(ApiResponse.success(emailService.mapToDto(saved)));
    }

    @PutMapping("/{id}/snooze")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<EmailEntityDto>> snoozeEmail(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until) {
        EmailEntity email = emailRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
        email.setStatus(EmailStatus.SNOOZED);
        email.setSnoozedUntil(until);
        EmailEntity saved = emailRepository.save(email);
        return ResponseEntity.ok(ApiResponse.success(emailService.mapToDto(saved)));
    }

    @GetMapping("/suggest")
        public ResponseEntity<ApiResponse<String>> suggestSearch(
            @AuthenticationPrincipal UserPrincipal principal, 
            @RequestParam String input) {
        String suggestion = aiService.suggestSearchQuery(input, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Suggestion generated", suggestion));
    }
}
