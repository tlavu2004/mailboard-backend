package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.request.*;
import com.awad.emailclientai.modules.email.dto.response.*;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.repository.UserRepository;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import com.awad.emailclientai.shared.service.EncryptionService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing email accounts and coordinating IMAP/SMTP operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailAccountService {

    private final EmailAccountRepository emailAccountRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final ImapService imapService;
    private final SmtpService smtpService;
    private final GmailWatchService gmailWatchService;

    /**
     * Connects a new email account for the user.
     */
    @Transactional
    public EmailAccountResponseDto connectAccount(Long userId, ConnectEmailAccountRequestDto request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // Check if account already exists
        EmailAccount account = emailAccountRepository.findByUserIdAndEmailAddress(userId, request.getEmailAddress())
                .orElseGet(() -> buildEmailAccount(user, request));

        if (account.getId() != null) {
            log.info("Updating existing email account: {} for user {}", request.getEmailAddress(), userId);
            updateEmailAccountDetails(account, request);
        }

        // Test the connection before saving
        boolean imapOk = imapService.testConnection(account);
        boolean smtpOk = smtpService.testConnection(account);

        if (!imapOk) {
            throw new IllegalArgumentException("Failed to connect to IMAP server. Please check your credentials.");
        }
        if (!smtpOk) {
            log.warn("SMTP connection failed for {}. Sending emails may not work.", request.getEmailAddress());
            account.setLastError("SMTP connection failed - sending may not work");
        }

        account.setLastSyncAt(LocalDateTime.now());
        EmailAccount saved = emailAccountRepository.save(account);

        // Start Gmail watch if it's a Gmail account
        if (saved.getProvider() == EmailProvider.GMAIL) {
            gmailWatchService.watchInbox(saved);
        }

        log.info("Email account connected: {} for user {}", request.getEmailAddress(), userId);
        return toResponseDto(saved);
    }

    /**
     * Gets all linked email accounts for a user.
     */
    public List<EmailAccountResponseDto> getAccounts(Long userId) {
        return emailAccountRepository.findByUserId(userId)
                .stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    /**
     * Gets a specific email account.
     */
    public EmailAccountResponseDto getAccount(Long userId, Long accountId) {
        EmailAccount account = getAccountForUser(userId, accountId);
        return toResponseDto(account);
    }

    /**
     * Disconnects (deletes) an email account.
     */
    @Transactional
    public void disconnectAccount(Long userId, Long accountId) {
        EmailAccount account = getAccountForUser(userId, accountId);
        emailAccountRepository.delete(account);
        log.info("Email account disconnected: {} for user {}", account.getEmailAddress(), userId);
    }

    /**
     * Gets all folders for an email account.
     */
    public List<MailFolderDto> getFolders(Long userId, Long accountId) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        return imapService.getFolders(account);
    }

    /**
     * Gets messages from a folder.
     */
    public List<MailMessageDto> getMessages(Long userId, Long accountId, String folder, 
                                             int page, int size) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        return imapService.getMessages(account, folder, page, size);
    }

    /**
     * Gets full message details.
     */
    public MailMessageDetailDto getMessageDetail(Long userId, Long accountId, 
                                                   String folder, long uid) 
            throws MessagingException, IOException {
        EmailAccount account = getAccountForUser(userId, accountId);
        return imapService.getMessageDetail(account, folder, uid);
    }

    /**
     * Marks a message as read/unread.
     */
    public void setMessageRead(Long userId, Long accountId, String folder, 
                                long uid, boolean read) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        imapService.setMessageRead(account, folder, uid, read);
    }

    /**
     * Marks a message as starred/flagged.
     */
    public void setMessageStarred(Long userId, Long accountId, String folder, 
                                   long uid, boolean starred) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        imapService.setMessageStarred(account, folder, uid, starred);
    }

    /**
     * Deletes a message.
     */
    public void deleteMessage(Long userId, Long accountId, String folder, 
                               long uid) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        imapService.deleteMessage(account, folder, uid);
    }

    /**
     * Sends an email and saves a copy to the Sent folder.
     */
    public String sendEmail(Long userId, Long accountId, 
                             SendEmailRequestDto request) throws MessagingException {
        EmailAccount account = getAccountForUser(userId, accountId);
        jakarta.mail.internet.MimeMessage message = smtpService.sendEmail(account, request);
        
        // Save to Sent folder using IMAP APPEND
        try {
            imapService.appendToSentFolder(account, message);
        } catch (Exception e) {
            log.warn("Failed to save email to Sent folder: {}", e.getMessage());
            // Don't throw - email was sent successfully, just couldn't save to Sent
        }
        
        return message.getMessageID();
    }

    /**
     * Sends an email with file attachments and saves a copy to the Sent folder.
     */
    public String sendEmailWithAttachments(Long userId, Long accountId, 
                                           SendEmailRequestDto request,
                                           MultipartFile[] attachments) throws MessagingException, IOException {
        EmailAccount account = getAccountForUser(userId, accountId);
        jakarta.mail.internet.MimeMessage message = smtpService.sendEmailWithAttachments(account, request, attachments);
        
        // Save to Sent folder using IMAP APPEND
        try {
            imapService.appendToSentFolder(account, message);
        } catch (Exception e) {
            log.warn("Failed to save email to Sent folder: {}", e.getMessage());
            // Don't throw - email was sent successfully, just couldn't save to Sent
        }
        
        return message.getMessageID();
    }

    /**
     * Downloads an attachment.
     */
    public AttachmentResourceDto downloadAttachment(Long userId, Long accountId, String folder, 
                                           long uid, String attachmentId) 
            throws MessagingException, IOException {
        EmailAccount account = getAccountForUser(userId, accountId);
        return imapService.downloadAttachment(account, folder, uid, attachmentId);
    }

    // ================== Private Helper Methods ==================

    private EmailAccount getAccountForUser(Long userId, Long accountId) {
        return emailAccountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));
    }

    private void updateEmailAccountDetails(EmailAccount account, ConnectEmailAccountRequestDto request) {
        // Update credentials
        account.setEncryptedPassword(encryptionService.encrypt(request.getPassword()));
        if (request.getRefreshToken() != null) {
            account.setEncryptedRefreshToken(encryptionService.encrypt(request.getRefreshToken()));
        }
        
        // Update other settings if provided
        if (request.getDisplayName() != null) account.setDisplayName(request.getDisplayName());
        if (request.getImapHost() != null) account.setImapHost(request.getImapHost());
        if (request.getImapPort() != null) account.setImapPort(request.getImapPort());
        if (request.getSmtpHost() != null) account.setSmtpHost(request.getSmtpHost());
        if (request.getSmtpPort() != null) account.setSmtpPort(request.getSmtpPort());
        if (request.getUsername() != null) account.setUsername(request.getUsername());
        
        account.setActive(true);
    }

    private EmailAccount buildEmailAccount(User user, ConnectEmailAccountRequestDto request) {
        EmailProvider provider = request.getProvider();
        
        // Use default settings if not provided
        String imapHost = request.getImapHost() != null ? request.getImapHost() 
                : provider.getDefaultImapHost();
        int imapPort = request.getImapPort() != null ? request.getImapPort() 
                : provider.getDefaultImapPort();
        String smtpHost = request.getSmtpHost() != null ? request.getSmtpHost() 
                : provider.getDefaultSmtpHost();
        int smtpPort = request.getSmtpPort() != null ? request.getSmtpPort() 
                : provider.getDefaultSmtpPort();

        // Encrypt sensitive data
        String encryptedPassword = encryptionService.encrypt(request.getPassword());
        String encryptedRefreshToken = request.getRefreshToken() != null 
                ? encryptionService.encrypt(request.getRefreshToken()) : null;

        return EmailAccount.builder()
                .user(user)
                .emailAddress(request.getEmailAddress())
                .displayName(request.getDisplayName() != null ? request.getDisplayName() 
                        : request.getEmailAddress())
                .provider(provider)
                .authType(request.getAuthType())
                .imapHost(imapHost)
                .imapPort(imapPort)
                .imapSsl(request.getImapSsl() != null ? request.getImapSsl() : true)
                .smtpHost(smtpHost)
                .smtpPort(smtpPort)
                .smtpStartTls(request.getSmtpStartTls() != null ? request.getSmtpStartTls() : true)
                .username(request.getUsername() != null ? request.getUsername() 
                        : request.getEmailAddress())
                .encryptedPassword(encryptedPassword)
                .encryptedRefreshToken(encryptedRefreshToken)
                .active(true)
                .build();
    }

    private EmailAccountResponseDto toResponseDto(EmailAccount account) {
        return EmailAccountResponseDto.builder()
                .id(account.getId())
                .emailAddress(account.getEmailAddress())
                .displayName(account.getDisplayName())
                .provider(account.getProvider())
                .authType(account.getAuthType())
                .imapHost(account.getImapHost())
                .imapPort(account.getImapPort())
                .smtpHost(account.getSmtpHost())
                .smtpPort(account.getSmtpPort())
                .active(account.getActive())
                .lastSyncAt(account.getLastSyncAt())
                .lastError(account.getLastError())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
