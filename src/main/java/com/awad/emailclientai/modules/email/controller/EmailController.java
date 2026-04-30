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
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emails")
@Tag(name = "Emails (Kanban)", description = "Manage persisted emails for Kanban workflow")
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
        
        return ResponseEntity.ok(ApiResponse.success("Sync started in background"));
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
    @Transactional
    public ResponseEntity<ApiResponse<EmailEntityDto>> sendEmailBridgeJson(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> jsonBody
    ) throws MessagingException {
        log.info("Bridge Send Email (JSON): Request from user {}", principal.getId());
        EmailAccount account = fetchPrimaryAccount(principal);
        SendEmailRequestDto request = mapJsonToDto(jsonBody);
        String messageId = emailAccountService.sendEmail(principal.getId(), account.getId(), request);
        
        // Proactively save to local DB
        EmailEntity entity = emailSyncService.saveLocalOutgoingEmail(account, request, messageId, "SENT", null);
        
        // Trigger background sync for Sent folder so the new message appears in the app exactly with provider data
        final Long acctIdJson = account.getId();
        new Thread(() -> {
            try {
                String sentFolderName = "[Gmail]/Sent Mail";
                emailSyncService.syncEmailsForAccount(acctIdJson, sentFolderName, 10, 0);
            } catch (Exception e) {
                log.warn("Post-send Sent sync failed for account {}: {}", acctIdJson, e.getMessage());
            }
        }).start();
        
        // Cleanup draft if it exists
        String draftId = request.getGmailDraftId();
        Long localEmailId = request.getLocalEmailId();
        log.info("[CLEANUP] Send success, checking for draft cleanup: gmailDraftId={}, localEmailId={}, inReplyTo={}", 
            draftId, localEmailId, request.getInReplyTo());

        if (draftId != null && !draftId.isEmpty() && !draftId.equals("undefined")) {
            try {
                emailAccountService.deleteDraft(principal.getId(), account.getId(), draftId);
                emailRepository.findByGmailDraftId(draftId).ifPresent(d -> {
                    // CRITICAL: Only delete if it's NOT the same record we just updated to SENT
                    if (!d.getId().equals(entity.getId())) {
                        emailRepository.delete(d);
                        log.info("[CLEANUP] Deleted duplicate draft record by gmailDraftId: {} (Previous status: {})", draftId, d.getStatus());
                    } else {
                        log.info("[CLEANUP] Skipping deletion because draft was successfully merged into SENT record ID: {}", d.getId());
                    }
                });
            } catch (Exception e) {
                log.warn("[CLEANUP] Failed to delete draft on Gmail: {}", e.getMessage());
            }
        } else if (localEmailId != null) {
            emailRepository.findById(localEmailId).ifPresent(d -> {
                log.info("[CLEANUP] Found local record {}, status={}, gmailMsgId={}", d.getId(), d.getStatus(), d.getGmailMessageId());
                if ("DRAFTS".equalsIgnoreCase(d.getStatus())) {
                    emailRepository.delete(d);
                    log.info("[CLEANUP] Deleted local draft record by localEmailId: {}", localEmailId);
                }
            });
        }

        // Aggressive cleanup by gmailMessageId to remove any ghost duplicates
        if (entity.getGmailMessageId() != null) {
            String gmMsgId = entity.getGmailMessageId();
            emailRepository.findByGmailMessageId(gmMsgId).ifPresent(d -> {
                if (!d.getId().equals(entity.getId()) && "DRAFTS".equalsIgnoreCase(d.getStatus())) {
                    emailRepository.delete(d);
                    log.info("[CLEANUP] Deleted ghost duplicate draft record by gmailMessageId: {}", gmMsgId);
                }
            });
        }
        
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", emailService.mapToDto(entity)));
    }

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bridge: Send Email (Multipart)")
    @Transactional
    public ResponseEntity<ApiResponse<EmailEntityDto>> sendEmailBridgeMultipart(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
            @RequestParam(value = "to", required = false) String toString,
            @RequestParam(value = "cc", required = false) String ccString,
            @RequestParam(value = "bcc", required = false) String bccString,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "threadId", required = false) String threadId,
            @RequestParam(value = "gmailDraftId", required = false) String gmailDraftId,
            @RequestParam(value = "localEmailId", required = false) Long localEmailId
    ) throws MessagingException, IOException {
        log.info("Bridge Send Email (Multipart): Request from user {}", principal.getId());
        EmailAccount account = fetchPrimaryAccount(principal);
        SendEmailRequestDto request = new SendEmailRequestDto();
        request.setGmailDraftId(gmailDraftId);
        request.setLocalEmailId(localEmailId);

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
        
        // Proactively save to local DB
        EmailEntity entity = emailSyncService.saveLocalOutgoingEmail(account, request, messageId, "SENT", null);

        // Trigger background sync for Sent folder
        final Long acctIdMulti = account.getId();
        new Thread(() -> {
            try {
                String sentFolderName = "[Gmail]/Sent Mail";
                emailSyncService.syncEmailsForAccount(acctIdMulti, sentFolderName, 10, 0);
            } catch (Exception e) {
                log.warn("Post-send Sent sync failed for account {}: {}", acctIdMulti, e.getMessage());
            }
        }).start();
        
        // Cleanup draft if it exists
        String dId = request.getGmailDraftId();
        Long lId = request.getLocalEmailId();
        log.info("[CLEANUP-MULTI] Send success, checking for draft cleanup: gmailDraftId={}, localEmailId={}", dId, lId);

        if (dId != null && !dId.isEmpty() && !dId.equals("undefined")) {
            try {
                emailAccountService.deleteDraft(principal.getId(), account.getId(), dId);
                emailRepository.findByGmailDraftId(dId).ifPresent(d -> {
                    if (!d.getId().equals(entity.getId())) {
                        emailRepository.delete(d);
                        log.info("[CLEANUP-MULTI] Deleted duplicate draft record by gmailDraftId: {} (Previous status: {})", dId, d.getStatus());
                    } else {
                        log.info("[CLEANUP-MULTI] Skipping deletion because draft was successfully merged into SENT record ID: {}", d.getId());
                    }
                });
            } catch (Exception e) {
                log.warn("[CLEANUP-MULTI] Failed to delete multipart draft on Gmail: {}", e.getMessage());
            }
        } else if (lId != null) {
            emailRepository.findById(lId).ifPresent(d -> {
                if (!d.getId().equals(entity.getId())) {
                    emailRepository.delete(d);
                    log.info("[CLEANUP-MULTI] Deleted local draft record by localEmailId: {}", lId);
                }
            });
        }

        // Aggressive cleanup by gmailMessageId to remove any ghost duplicates
        if (entity.getGmailMessageId() != null) {
            String gmMsgId = entity.getGmailMessageId();
            emailRepository.findByGmailMessageId(gmMsgId).ifPresent(d -> {
                if (!d.getId().equals(entity.getId()) && "DRAFTS".equalsIgnoreCase(d.getStatus())) {
                    emailRepository.delete(d);
                    log.info("[CLEANUP-MULTI] Deleted ghost duplicate draft record by gmailMessageId: {}", gmMsgId);
                }
            });
        }
        
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", emailService.mapToDto(entity)));
    }

    @PostMapping({"/draft", "/drafts"})
    @Operation(summary = "Save or Update Draft")
    @Transactional
    public ResponseEntity<ApiResponse<EmailEntityDto>> saveDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody SendEmailRequestDto request) throws MessagingException, IOException {
        EmailAccount account = fetchPrimaryAccount(principal);
        String draftId = request.getGmailDraftId();
        Map<String, String> draftData = null;
        
        // 1. Try to recover draftId from local emailId if not provided in request
        String recoveredDraftId = draftId;
        if ((recoveredDraftId == null || recoveredDraftId.isEmpty()) && request.getLocalEmailId() != null) {
            var existingOpt = emailRepository.findById(request.getLocalEmailId());
            if (existingOpt.isPresent() && existingOpt.get().getGmailDraftId() != null) {
                recoveredDraftId = existingOpt.get().getGmailDraftId();
                request.setGmailDraftId(recoveredDraftId);
            }
        }
        final String finalDraftIdForGmail = recoveredDraftId;

        // 2. Perform Save or Update on Gmail
        boolean isNewDraft = (finalDraftIdForGmail == null || finalDraftIdForGmail.isEmpty());
        if (!isNewDraft) {
            draftData = emailAccountService.updateDraft(principal.getId(), account.getId(), finalDraftIdForGmail, request);
        } else {
            draftData = emailAccountService.saveDraft(principal.getId(), account.getId(), request);
        }
        
        final String finalDraftId = draftData != null ? draftData.get("draftId") : finalDraftIdForGmail;
        final String gmMsgId = draftData != null ? draftData.get("messageId") : null;
        
        // Proactively save to local DB
        EmailEntity entity;
        try {
            entity = emailSyncService.saveLocalOutgoingEmail(account, request, "DRAFT-" + finalDraftId, "DRAFTS", gmMsgId);
            entity.setGmailDraftId(finalDraftId);
            if (gmMsgId != null) {
                entity.setGmailMessageId(gmMsgId);
            }
            entity = emailRepository.save(entity);
            
            // 3. CRITICAL: If we just created a NEW draft for an EXISTING local record,
            // we must delete the old record to prevent duplicates.
            if (isNewDraft && request.getLocalEmailId() != null) {
                Long oldId = request.getLocalEmailId();
                if (!entity.getId().equals(oldId)) {
                    emailRepository.deleteById(oldId);
                    log.info("Deleted old duplicate draft record {} after creating new draft {}", oldId, entity.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Conflict or error during proactive draft save, attempting fallback lookup: {}", e.getMessage());
            entity = emailRepository.findByGmailDraftId(finalDraftId)
                    .orElseGet(() -> emailRepository.findByMessageId("DRAFT-" + finalDraftId).orElse(new EmailEntity()));
            
            entity.setAccount(account);
            entity.setGmailDraftId(finalDraftId);
            entity.setGmailMessageId(gmMsgId);
            entity.setStatus("DRAFTS");
            entity.setSubject(request.getSubject());
            entity.setBody(request.getBodyHtml() != null ? request.getBodyHtml() : request.getBodyText());
            entity = emailRepository.save(entity);
        }
        
        return ResponseEntity.ok(ApiResponse.success("Draft saved", emailService.mapToDto(entity)));
    }

    @DeleteMapping("/draft/{draftId}")
    @Operation(summary = "Discard and Delete Draft")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> deleteDraft(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String draftId,
            @RequestParam(required = false) Long emailId) throws IOException {
        EmailAccount account = fetchPrimaryAccount(principal);
        
        // 1. Move to Trash on Gmail if we have a valid Gmail Message ID
        if (draftId != null && !draftId.isEmpty() && !draftId.equals("undefined")) {
            try {
                // To trash a draft in Gmail, we should trash its associated message
                // We fetch the entity to get the gmailMessageId
                EmailEntity entity = emailId != null 
                    ? emailRepository.findById(emailId).orElse(null)
                    : emailRepository.findByGmailDraftId(draftId).orElse(null);
                
                if (entity != null && entity.getGmailMessageId() != null) {
                    emailAccountService.trashDraft(principal.getId(), account.getId(), entity.getGmailMessageId());
                    log.info("Trashed Gmail draft message: {}", entity.getGmailMessageId());
                } else {
                    // Fallback to permanent delete if we don't have messageId
                    emailAccountService.deleteDraft(principal.getId(), account.getId(), draftId);
                }
            } catch (Exception e) {
                log.warn("Failed to trash/delete draft from Gmail (id: {}): {}", draftId, e.getMessage());
            }
        }
        
        // 2. Update local repository status to TRASH instead of deleting
        if (emailId != null) {
            emailRepository.findById(emailId).ifPresent(entity -> {
                entity.setPreviousStatus(entity.getStatus());
                entity.setStatus("TRASH");
                entity.setDeletedAt(java.time.LocalDateTime.now());
                emailRepository.save(entity);
                log.info("Moved local draft record to TRASH: {}", emailId);
            });
        } else if (draftId != null && !draftId.isEmpty() && !draftId.equals("undefined")) {
            emailRepository.findByGmailDraftId(draftId).ifPresent(entity -> {
                entity.setPreviousStatus(entity.getStatus());
                entity.setStatus("TRASH");
                entity.setDeletedAt(java.time.LocalDateTime.now());
                emailRepository.save(entity);
                log.info("Moved local draft record to TRASH by draftId: {}", draftId);
            });
        }
        
        return ResponseEntity.ok(ApiResponse.success("Draft discarded"));
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
            request.setGmailDraftId((String) jsonBody.get("gmailDraftId"));
            Object localId = jsonBody.get("localEmailId");
            if (localId != null) {
                if (localId instanceof Number) request.setLocalEmailId(((Number) localId).longValue());
                else if (localId instanceof String) request.setLocalEmailId(Long.parseLong((String) localId));
            }
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
        
        emailService.syncStatusToProvider(email.getId(), previousStatus, status);
        
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
