package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.request.SendEmailRequestDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.shared.service.EncryptionService;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.Properties;

/**
 * Service for sending emails via SMTP protocol.
 * Supports both Basic Authentication and OAuth2 (XOAUTH2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmtpService {

    private final EncryptionService encryptionService;
    private final GoogleTokenService googleTokenService;

    /**
     * Sends an email using the given account's SMTP settings.
     *
     * @param account The email account to send from
     * @param request The email composition details
     * @return The sent MimeMessage (for IMAP APPEND to Sent folder)
     */
    public MimeMessage sendEmail(EmailAccount account, SendEmailRequestDto request) throws MessagingException {
        return sendEmailInternal(account, request, true);
    }

    private MimeMessage sendEmailInternal(EmailAccount account, SendEmailRequestDto request, boolean retryOnAuthFailure) throws MessagingException {
        Properties props = createSmtpProperties(account);
        
        String password = encryptionService.decrypt(account.getEncryptedPassword());
        
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.getUsername(), password);
            }
        });
        
        // Enable debug logging in development
        session.setDebug(log.isDebugEnabled());
        
        MimeMessage message = createMimeMessage(session, account, request);
        
        try {
            Transport.send(message);
        } catch (AuthenticationFailedException e) {
            if (retryOnAuthFailure && account.getAuthType() == EmailAuthType.OAUTH2) {
                log.info("SMTP authentication failed for {}, attempting token refresh...", account.getEmailAddress());
                String newAccessToken = googleTokenService.refreshAccessToken(account);
                if (newAccessToken != null) {
                    return sendEmailInternal(account, request, false);
                }
            }
            throw e;
        }
        
        log.info("Email sent successfully from {} to {}", 
                account.getEmailAddress(), request.getTo());
        
        return message;
    }

    /**
     * Sends an email with file attachments using the given account's SMTP settings.
     *
     * @param account     The email account to send from
     * @param request     The email composition details
     * @param attachments Array of files to attach
     * @return The sent MimeMessage (for IMAP APPEND to Sent folder)
     */
    public MimeMessage sendEmailWithAttachments(EmailAccount account, SendEmailRequestDto request, 
                                           MultipartFile[] attachments) throws MessagingException, IOException {
        return sendEmailWithAttachmentsInternal(account, request, attachments, true);
    }

    private MimeMessage sendEmailWithAttachmentsInternal(EmailAccount account, SendEmailRequestDto request, 
                                           MultipartFile[] attachments, boolean retryOnAuthFailure) throws MessagingException, IOException {
        Properties props = createSmtpProperties(account);
        
        String password = encryptionService.decrypt(account.getEncryptedPassword());
        
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.getUsername(), password);
            }
        });
        
        session.setDebug(log.isDebugEnabled());
        
        MimeMessage message = createMimeMessageWithAttachments(session, account, request, attachments);
        
        try {
            Transport.send(message);
        } catch (AuthenticationFailedException e) {
            if (retryOnAuthFailure && account.getAuthType() == EmailAuthType.OAUTH2) {
                log.info("SMTP (attachments) authentication failed for {}, attempting token refresh...", account.getEmailAddress());
                String newAccessToken = googleTokenService.refreshAccessToken(account);
                if (newAccessToken != null) {
                    return sendEmailWithAttachmentsInternal(account, request, attachments, false);
                }
            }
            throw e;
        }
        
        log.info("Email with {} attachments sent from {} to {}", 
                attachments != null ? attachments.length : 0,
                account.getEmailAddress(), request.getTo());
        
        return message;
    }

    /**
     * Tests SMTP connection for an account.
     */
    public boolean testConnection(EmailAccount account) {
        return testConnectionInternal(account, true);
    }

    private boolean testConnectionInternal(EmailAccount account, boolean retryOnAuthFailure) {
        try {
            Properties props = createSmtpProperties(account);
            String password = encryptionService.decrypt(account.getEncryptedPassword());
            
            Session session = Session.getInstance(props);
            Transport transport = session.getTransport("smtp");
            try {
                transport.connect(account.getSmtpHost(), account.getSmtpPort(), 
                        account.getUsername(), password);
            } catch (AuthenticationFailedException e) {
                if (retryOnAuthFailure && account.getAuthType() == EmailAuthType.OAUTH2) {
                    log.info("SMTP test connection failed for {}, attempting token refresh...", account.getEmailAddress());
                    String newAccessToken = googleTokenService.refreshAccessToken(account);
                    if (newAccessToken != null) {
                        return testConnectionInternal(account, false);
                    }
                }
                throw e;
            }
            transport.close();
            
            return true;
        } catch (Exception e) {
            log.error("SMTP connection test failed for {}: {}", 
                    account.getEmailAddress(), e.getMessage());
            return false;
        }
    }

    // ================== Private Helper Methods ==================

    private Properties createSmtpProperties(EmailAccount account) {
        Properties props = new Properties();
        props.put("mail.smtp.host", account.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(account.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        
        if (account.getSmtpStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        
        // SSL settings for port 465
        if (account.getSmtpPort() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", "465");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }
        
        props.put("mail.smtp.ssl.trust", account.getSmtpHost());
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "30000");
        
        // OAuth2 configuration
        if (account.getAuthType() == EmailAuthType.OAUTH2) {
            props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            props.put("mail.smtp.sasl.enable", "true");
            props.put("mail.smtp.sasl.mechanisms", "XOAUTH2");
        }
        
        return props;
    }

    private MimeMessage createMimeMessage(Session session, EmailAccount account, 
                                           SendEmailRequestDto request) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        
        // From
        message.setFrom(new InternetAddress(account.getEmailAddress()));
        
        // To
        if (request.getTo() != null && !request.getTo().isEmpty()) {
            for (String to : request.getTo()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
        }
        
        // CC
        if (request.getCc() != null && !request.getCc().isEmpty()) {
            for (String cc : request.getCc()) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
            }
        }
        
        // BCC
        if (request.getBcc() != null && !request.getBcc().isEmpty()) {
            for (String bcc : request.getBcc()) {
                message.addRecipient(Message.RecipientType.BCC, new InternetAddress(bcc));
            }
        }
        
        // Subject
        message.setSubject(request.getSubject(), "UTF-8");
        
        // Date
        message.setSentDate(new Date());
        
        // Reply headers (for threading)
        if (request.getInReplyTo() != null && !request.getInReplyTo().isEmpty()) {
            message.setHeader("In-Reply-To", request.getInReplyTo());
        }
        if (request.getReferences() != null && !request.getReferences().isEmpty()) {
            message.setHeader("References", String.join(" ", request.getReferences()));
        }
        
        // Body (prefer HTML if available, fallback to plain text)
        if (request.getBodyHtml() != null && !request.getBodyHtml().isEmpty()) {
            // Multipart message with both text and HTML
            MimeMultipart multipart = new MimeMultipart("alternative");
            
            // Plain text part
            if (request.getBodyText() != null && !request.getBodyText().isEmpty()) {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(request.getBodyText(), "UTF-8");
                multipart.addBodyPart(textPart);
            }
            
            // HTML part
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(request.getBodyHtml(), "text/html; charset=UTF-8");
            multipart.addBodyPart(htmlPart);
            
            message.setContent(multipart);
        } else if (request.getBodyText() != null) {
            // Plain text only
            message.setText(request.getBodyText(), "UTF-8");
        } else {
            message.setText("", "UTF-8");
        }
        
        return message;
    }

    private MimeMessage createMimeMessageWithAttachments(Session session, EmailAccount account,
                                                         SendEmailRequestDto request, 
                                                         MultipartFile[] attachments) throws MessagingException, IOException {
        MimeMessage message = new MimeMessage(session);
        
        // From
        message.setFrom(new InternetAddress(account.getEmailAddress()));
        
        // To
        if (request.getTo() != null && !request.getTo().isEmpty()) {
            for (String to : request.getTo()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
        }
        
        // CC
        if (request.getCc() != null && !request.getCc().isEmpty()) {
            for (String cc : request.getCc()) {
                message.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
            }
        }
        
        // BCC
        if (request.getBcc() != null && !request.getBcc().isEmpty()) {
            for (String bcc : request.getBcc()) {
                message.addRecipient(Message.RecipientType.BCC, new InternetAddress(bcc));
            }
        }
        
        // Subject
        message.setSubject(request.getSubject(), "UTF-8");
        
        // Date
        message.setSentDate(new Date());
        
        // Reply headers (for threading)
        if (request.getInReplyTo() != null && !request.getInReplyTo().isEmpty()) {
            message.setHeader("In-Reply-To", request.getInReplyTo());
        }
        if (request.getReferences() != null && !request.getReferences().isEmpty()) {
            message.setHeader("References", String.join(" ", request.getReferences()));
        }
        
        // Use "mixed" multipart for body + attachments
        MimeMultipart mixedMultipart = new MimeMultipart("mixed");
        
        // Body part (can be alternative text/html or plain text)
        MimeBodyPart bodyPart = new MimeBodyPart();
        if (request.getBodyHtml() != null && !request.getBodyHtml().isEmpty()) {
            // Multipart alternative for text + HTML
            MimeMultipart alternativeMultipart = new MimeMultipart("alternative");
            
            if (request.getBodyText() != null && !request.getBodyText().isEmpty()) {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(request.getBodyText(), "UTF-8");
                alternativeMultipart.addBodyPart(textPart);
            }
            
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(request.getBodyHtml(), "text/html; charset=UTF-8");
            alternativeMultipart.addBodyPart(htmlPart);
            
            bodyPart.setContent(alternativeMultipart);
        } else if (request.getBodyText() != null) {
            bodyPart.setText(request.getBodyText(), "UTF-8");
        } else {
            bodyPart.setText("", "UTF-8");
        }
        mixedMultipart.addBodyPart(bodyPart);
        
        // Attachment parts
        if (attachments != null) {
            for (MultipartFile file : attachments) {
                if (file != null && !file.isEmpty()) {
                    MimeBodyPart attachmentPart = new MimeBodyPart();
                    attachmentPart.setFileName(file.getOriginalFilename());
                    attachmentPart.setContent(file.getBytes(), file.getContentType());
                    attachmentPart.setHeader("Content-Transfer-Encoding", "base64");
                    mixedMultipart.addBodyPart(attachmentPart);
                }
            }
        }
        
        message.setContent(mixedMultipart);
        
        return message;
    }
}
