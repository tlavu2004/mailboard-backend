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

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info(">>>> [X-RAY-RELOADED-V10] Initialized and Monitoring Rendering <<<<");
    }

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
                                String.format("emails/%d/attachments/%d/inline", entity.getId(), at.getId()) :
                                String.format("emails/%d/attachments/%d/download", entity.getId(), at.getId()))
                        .build())
                .collect(Collectors.toList());

        // Transform body for all emails (Sanitization, Meta-fix, CID resolution)
        body = processEmailBody(body, entity.getId(), entity.getAttachments());

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

    public String processEmailBody(String html, Long emailId, List<EmailAttachment> attachments) {
        if (html == null) return null;
        
        log.info("[V10-DEBUG-START] Processing Email ID: {}", emailId);
        
        String resolvedHtml = html;
        int scriptCount = 0;
        int onEventCount = 0;
        int jsUrlCount = 0;
        int frameCount = 0;
        int styleStrippedCount = 0;

        // 1. X-Ray Reloaded V10 Sanitization
        
        // Strip <script>
        java.util.regex.Pattern scriptPattern = java.util.regex.Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
        java.util.regex.Matcher scriptMatcher = scriptPattern.matcher(resolvedHtml);
        while (scriptMatcher.find()) scriptCount++;
        resolvedHtml = scriptMatcher.replaceAll("");

        // Strip dangerous containers: <iframe>, <embed>, <object>, <base>, <link>, <meta>, <applet>, <form>
        java.util.regex.Pattern framePattern = java.util.regex.Pattern.compile("(?is)<(iframe|embed|object|base|link|meta|applet|form)\\b[^>]*>.*?</\\1>|<(iframe|embed|object|base|link|meta|applet|form)\\b[^>]*>");
        java.util.regex.Matcher frameMatcher = framePattern.matcher(resolvedHtml);
        while (frameMatcher.find()) frameCount++;
        resolvedHtml = frameMatcher.replaceAll("");

        // Strip ALL 'on*' event attributes (aggressive purge)
        java.util.regex.Pattern onEventPattern = java.util.regex.Pattern.compile("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
        java.util.regex.Matcher onMatcher = onEventPattern.matcher(resolvedHtml);
        while (onMatcher.find()) onEventCount++;
        resolvedHtml = onMatcher.replaceAll("");

        // Strip 'javascript:' URLs
        java.util.regex.Pattern jsUrlPattern = java.util.regex.Pattern.compile("(?i)(href|src)\\s*=\\s*(\"javascript:[^\"]*\"|'javascript:[^']*'|javascript:[^\\s>]+)");
        java.util.regex.Matcher jsMatcher = jsUrlPattern.matcher(resolvedHtml);
        while (jsMatcher.find()) jsUrlCount++;
        resolvedHtml = jsMatcher.replaceAll("$1=\"#\"");

        // Sanitize <style> tags (remove blocks with expressions or imports)
        java.util.regex.Pattern stylePattern = java.util.regex.Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
        java.util.regex.Matcher styleMatcher = stylePattern.matcher(resolvedHtml);
        StringBuffer styleSb = new StringBuffer();
        while (styleMatcher.find()) {
            String content = styleMatcher.group(1);
            if (content.toLowerCase().matches(".*(expression|javascript|@import|url\\s*\\().*")) {
                styleStrippedCount++;
                styleMatcher.appendReplacement(styleSb, "");
            } else {
                styleMatcher.appendReplacement(styleSb, styleMatcher.group(0));
            }
        }
        styleMatcher.appendTail(styleSb);
        resolvedHtml = styleSb.toString();

        log.info("[V10-SHIELD-AUDIT] Email {}: Stripped {} scripts, {} frames/meta, {} on* events, {} js-urls, {} styles", 
                emailId, scriptCount, frameCount, onEventCount, jsUrlCount, styleStrippedCount);

        if (attachments == null || attachments.isEmpty()) {
            log.info("[V7-DEBUG-END] No attachments found for email {}", emailId);
            return resolvedHtml;
        }

        log.debug("[Rendering] Checking CID replacements for email ID: {}", emailId);
        
        for (EmailAttachment at : attachments) {
            if (at.isInline() && at.getContentId() != null) {
                // Normalize Content-ID (strip brackets if present)
                String cid = at.getContentId().replaceAll("[<>]", "").trim();
                String proxyUrl = String.format("/api/v1/emails/%d/attachments/%d/inline", emailId, at.getId());
                
                // Use a Case-Insensitive regex to find 'cid:[<]ID[>]'
                java.util.regex.Pattern cidPattern = java.util.regex.Pattern.compile("(?i)cid:<?(" + java.util.regex.Pattern.quote(cid) + ")>?");
                java.util.regex.Matcher cidMatcher = cidPattern.matcher(resolvedHtml);
                
                int count = 0;
                while (cidMatcher.find()) count++;
                
                if (count > 0) {
                    resolvedHtml = cidMatcher.replaceAll(proxyUrl);
                    log.info("[Rendering] Successfully replaced {} matches for CID: {} in Email ID: {}", count, cid, emailId);
                }
            }
        }
        // 3. Inject Height Bridge Script for Secure Auto-Resize (V10.20 - Balanced Shield)
        String bridgeScript = "<script>" +
            "function sendHeight() {" +
            "  document.body.style.margin = '0';" +
            "  document.body.style.overflowY = 'hidden';" + // Force expand vertically
            "  document.body.style.overflowX = 'auto';" +  // Allow horizontal scroll
            "  document.documentElement.style.overflowX = 'auto';" +
            "  var height = Math.max(document.body.offsetHeight, document.body.scrollHeight);" +
            "  window.parent.postMessage({ type: 'MB_RESIZE', height: height }, '*');" +
            "}" +
            "window.onload = sendHeight;" +
            "window.onresize = sendHeight;" +
            "setInterval(sendHeight, 1000);" +
            "</script>";
        
        resolvedHtml = resolvedHtml + bridgeScript;

        return resolvedHtml;
    }
}
