package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.dto.request.*;
import com.awad.emailclientai.modules.email.dto.response.*;
import com.awad.emailclientai.modules.email.service.EmailAccountService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * REST Controller for managing linked email accounts and IMAP operations.
 * This controller handles real email data from external mail servers (Gmail, Outlook, etc.).
 */
@RestController
@RequestMapping("/api/v1/email-accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Email Accounts", description = "Manage linked email accounts and real email data")
public class EmailAccountController {

    private final EmailAccountService emailAccountService;

    // ==================== Account Management ====================

    @PostMapping("/connect")
    @Operation(summary = "Connect a new email account", 
               description = "Link a new email account (Gmail, Outlook, etc.) using IMAP credentials. Only available for local login users.")
    public ResponseEntity<ApiResponse<EmailAccountResponseDto>> connectAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ConnectEmailAccountRequestDto request) {
        
        // Enforce Restricted Social Mode: only LOCAL users can link multiple accounts
        if (principal.getAuthProvider() != com.awad.emailclientai.modules.user.entity.AuthProvider.LOCAL) {
            throw new com.awad.emailclientai.shared.exception.BusinessException(
                    com.awad.emailclientai.shared.exception.ErrorCode.EMAIL_LINKING_DISABLED_FOR_SOCIAL);
        }
        
        log.info("Connecting email account {} for user {}", 
                request.getEmailAddress(), principal.getId());
        
        EmailAccountResponseDto response = emailAccountService.connectAccount(
                principal.getId(), request);
        
        return ResponseEntity.ok(ApiResponse.success("Email account connected successfully", 
                response));
    }

    @GetMapping
    @Operation(summary = "List all linked email accounts")
    public ResponseEntity<ApiResponse<List<EmailAccountResponseDto>>> getAccounts(
            @AuthenticationPrincipal UserPrincipal principal) {
        
        List<EmailAccountResponseDto> accounts = emailAccountService.getAccounts(principal.getId());
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get a specific email account")
    public ResponseEntity<ApiResponse<EmailAccountResponseDto>> getAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId) {
        
        EmailAccountResponseDto account = emailAccountService.getAccount(
                principal.getId(), accountId);
        return ResponseEntity.ok(ApiResponse.success(account));
    }

    @DeleteMapping("/{accountId}")
    @Operation(summary = "Disconnect an email account")
    public ResponseEntity<ApiResponse<String>> disconnectAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId) {
        
        emailAccountService.disconnectAccount(principal.getId(), accountId);
        return ResponseEntity.ok(ApiResponse.success(
                "Email account disconnected successfully"));
    }

    // ==================== Folder Operations ====================

    @GetMapping("/{accountId}/folders")
    @Operation(summary = "List all folders/mailboxes for an account")
    public ResponseEntity<ApiResponse<List<MailFolderDto>>> getFolders(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId) throws MessagingException {
        
        List<MailFolderDto> folders = emailAccountService.getFolders(
                principal.getId(), accountId);
        return ResponseEntity.ok(ApiResponse.success(folders));
    }

    // ==================== Message Operations ====================

    @GetMapping("/{accountId}/folders/{folder}/messages")
    @Operation(summary = "List messages in a folder with pagination")
    public ResponseEntity<ApiResponse<List<MailMessageDto>>> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) throws MessagingException {
        
        List<MailMessageDto> messages = emailAccountService.getMessages(
                principal.getId(), accountId, folder, page, size);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @GetMapping("/{accountId}/folders/{folder}/messages/{uid}")
    @Operation(summary = "Get full message details including body")
    public ResponseEntity<ApiResponse<MailMessageDetailDto>> getMessageDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @PathVariable long uid) throws MessagingException, IOException {
        
        MailMessageDetailDto message = emailAccountService.getMessageDetail(
                principal.getId(), accountId, folder, uid);
        return ResponseEntity.ok(ApiResponse.success(message));
    }

    @PatchMapping("/{accountId}/folders/{folder}/messages/{uid}/read")
    @Operation(summary = "Mark a message as read or unread")
    public ResponseEntity<ApiResponse<String>> setMessageRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @PathVariable long uid,
            @Valid @RequestBody UpdateEmailReadStatusRequestDto request) throws MessagingException {
        
        emailAccountService.setMessageRead(principal.getId(), accountId, folder, uid, request.getRead());
        return ResponseEntity.ok(ApiResponse.success(
                request.getRead() ? "Message marked as read" : "Message marked as unread"));
    }

    @PatchMapping("/{accountId}/folders/{folder}/messages/{uid}/star")
    @Operation(summary = "Star or unstar a message")
    public ResponseEntity<ApiResponse<String>> setMessageStarred(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @PathVariable long uid,
            @Valid @RequestBody UpdateEmailStarStatusRequestDto request) throws MessagingException {
        
        emailAccountService.setMessageStarred(principal.getId(), accountId, folder, uid, request.getStarred());
        return ResponseEntity.ok(ApiResponse.success(
                request.getStarred() ? "Message starred" : "Message unstarred"));
    }

    @DeleteMapping("/{accountId}/folders/{folder}/messages/{uid}")
    @Operation(summary = "Delete a message")
    public ResponseEntity<ApiResponse<String>> deleteMessage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @PathVariable long uid) throws MessagingException {
        
        emailAccountService.deleteMessage(principal.getId(), accountId, folder, uid);
        return ResponseEntity.ok(ApiResponse.success("Message deleted"));
    }

    // ==================== Send Email ====================

    @PostMapping("/{accountId}/send")
    @Operation(summary = "Send an email using the linked account")
    public ResponseEntity<ApiResponse<String>> sendEmail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @Valid @RequestBody SendEmailRequestDto request) throws MessagingException {
        
        String messageId = emailAccountService.sendEmail(principal.getId(), accountId, request);
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", messageId));
    }

    // ==================== Attachments ====================

    @GetMapping("/{accountId}/folders/{folder}/messages/{uid}/attachments/{attachmentId}")
    @Operation(summary = "Download an attachment")
    public ResponseEntity<InputStreamResource> downloadAttachment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long accountId,
            @PathVariable String folder,
            @PathVariable long uid,
            @PathVariable String attachmentId) throws MessagingException, IOException {
        
        AttachmentResourceDto attachment = emailAccountService.downloadAttachment(
                principal.getId(), accountId, folder, uid, attachmentId);
        
        String filename = attachment.getFilename();
        if (filename == null || filename.isBlank()) {
            filename = "attachment";
        }

        // 1. Try to parse Content-Type from DTO
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        String contentType = attachment.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            if (contentType.contains(";")) {
                contentType = contentType.split(";")[0].trim();
            }
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception e) {
                // ignore
            }
        }

        // 2. Robust Override: Force correct MediaType based on file extension
        // This fixes issues where email servers send generic "application/octet-stream"
        // or where Windows/Postman fails to infer the type for "Save as" dialog.
        String lowerFn = filename.toLowerCase();
        if (lowerFn.endsWith(".txt")) {
            mediaType = MediaType.TEXT_PLAIN;
        } else if (lowerFn.endsWith(".pdf")) {
            mediaType = MediaType.APPLICATION_PDF;
        } else if (lowerFn.endsWith(".jpg") || lowerFn.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
        } else if (lowerFn.endsWith(".png")) {
            mediaType = MediaType.IMAGE_PNG;
        } else if (lowerFn.endsWith(".html") || lowerFn.endsWith(".htm")) {
            mediaType = MediaType.TEXT_HTML;
        }

        // Use simple string format for maximum Windows/Postman compatibility
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new InputStreamResource(attachment.getInputStream()));
    }
}
