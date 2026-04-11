package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.entity.EmailAttachment;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.repository.EmailAttachmentRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final EmailRepository emailRepository;
    private final EmailAttachmentRepository attachmentRepository;
    private final ImapService imapService;

    @Transactional(readOnly = true)
    public EmailEntityDto getEmailDetail(Long id) {
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
        
        return mapToDto(email);
    }

    @Transactional(readOnly = true)
    public Resource getInlineAttachment(Long emailId, Long attachmentId) throws MessagingException, IOException {
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
        
        EmailAttachment at = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        
        var attachmentResource = imapService.downloadAttachment(
                email.getAccount(), 
                "INBOX", 
                email.getUid(), 
                at.getServerAttachmentId()
        );
        
        return new InputStreamResource(attachmentResource.getInputStream());
    }

    public String getAttachmentContentType(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .map(EmailAttachment::getContentType)
                .orElse("application/octet-stream");
    }

    public EmailEntityDto mapToDto(EmailEntity entity) {
        String body = entity.getBody();
        List<EmailEntityDto.AttachmentDto> attachments = entity.getAttachments().stream()
                .map(at -> EmailEntityDto.AttachmentDto.builder()
                        .id(at.getId().toString())
                        .filename(at.getFilename())
                        .size(at.getSize())
                        .contentType(at.getContentType())
                        .serverAttachmentId(at.getServerAttachmentId())
                        .contentId(at.getContentId())
                        .inline(at.isInline())
                        .url(at.isInline() ? 
                                String.format("/api/v1/emails/%d/attachments/%d/inline", entity.getId(), at.getId()) :
                                String.format("/api/v1/email-accounts/%d/folders/INBOX/messages/%d/attachments/%s", 
                                        entity.getAccount().getId(), entity.getUid(), at.getServerAttachmentId()))
                        .build())
                .collect(Collectors.toList());

        // Transform body to resolve inline images (cid:)
        if (body != null && body.contains("cid:")) {
            body = resolveInlineImages(body, entity.getId(), entity.getAttachments());
        }

        return EmailEntityDto.builder()
                .id(entity.getId())
                .messageId(entity.getMessageId())
                .uid(entity.getUid())
                .subject(entity.getSubject())
                .sender(entity.getSender())
                .snippet(entity.getSnippet())
                .body(body)
                .status(entity.getStatus())
                .receivedDate(entity.getReceivedDate())
                .snoozedUntil(entity.getSnoozedUntil())
                .summary(entity.getSummary())
                .summarySource(entity.getSummarySource() != null ? entity.getSummarySource().name() : null)
                .isRead(entity.isRead())
                .hasAttachments(entity.isHasAttachments())
                .accountEmail(entity.getAccount().getEmailAddress())
                .attachments(attachments)
                .gmailLink(entity.getGmailMessageId() != null ? 
                        String.format("https://mail.google.com/mail/u/%s/#inbox/%s", 
                            URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                            entity.getGmailMessageId()) :
                        String.format("https://mail.google.com/mail/u/%s/#search/rfc822msgid:%s", 
                            URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                            URLEncoder.encode(entity.getMessageId(), StandardCharsets.UTF_8)))
                .build();
    }

    private String resolveInlineImages(String html, Long emailId, List<EmailAttachment> attachments) {
        if (html == null || attachments == null) return html;
        String resolvedHtml = html;
        for (EmailAttachment at : attachments) {
            if (at.isInline() && at.getContentId() != null) {
                // Normalize Content-ID (strip brackets if present)
                String cid = at.getContentId().replaceAll("[<>]", "");
                String proxyUrl = String.format("/api/v1/emails/%d/attachments/%d/inline", emailId, at.getId());
                
                // Replace both with-brackets and without-brackets versions in HTML
                resolvedHtml = resolvedHtml.replace("cid:" + cid, proxyUrl);
                resolvedHtml = resolvedHtml.replace("cid:<" + cid + ">", proxyUrl);
            }
        }
        return resolvedHtml;
    }
}
