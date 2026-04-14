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
        final String finalBody = body; // Make it effectively final for lambda

        List<EmailEntityDto.AttachmentDto> attachments = entity.getAttachments().stream()
                .filter(at -> {
                    boolean isImg = at.getContentType() != null && at.getContentType().startsWith("image/");
                    if (isImg) {
                        if (at.isInline() || at.getContentId() != null) {
                            return false; // Standard inline image
                        }
                        // V10.36 Edge Case: If the image filename is explicitly referenced in the HTML body 
                        // (e.g. as an 'alt' attribute or src), it is acting as an inline image/banner.
                        if (at.getFilename() != null && !at.getFilename().isEmpty() && 
                            finalBody != null && finalBody.contains(at.getFilename())) {
                            return false; 
                        }
                    }
                    return true;
                })
                .map(at -> EmailEntityDto.AttachmentDto.builder()
                        .id(at.getId().toString())
                        .filename(at.getFilename())
                        .size(at.getSize())
                        .contentType(at.getContentType())
                        .serverAttachmentId(at.getServerAttachmentId())
                        .contentId(at.getContentId())
                        .inline(at.isInline() || at.getContentId() != null)
                        .externalUrl(at.getExternalUrl())
                        .url(at.getExternalUrl() != null ? at.getExternalUrl() : (at.isInline() || at.getContentId() != null ? 
                                String.format("emails/%d/attachments/%d/inline", entity.getId(), at.getId()) :
                                String.format("emails/%d/attachments/%d/download", entity.getId(), at.getId())))
                        .build())
                .collect(Collectors.toList());

        // Transform body for all emails (Sanitization, Meta-fix, CID resolution)
        body = processEmailBody(body, entity.getId(), entity.getAttachments());

        // V10.32: Dynamic Discovery Fallback for existing emails
        imapService.scanForCloudLinksEntityDto(body, attachments, new int[]{attachments.size()});

        // V10.35: Final flag calculation for accurate UI indicators
        boolean hasCloud = attachments.stream().anyMatch(a -> a.getExternalUrl() != null);
        boolean hasPhysical = attachments.stream().anyMatch(a -> a.getExternalUrl() == null);

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
                .hasAttachments(hasCloud || hasPhysical)
                .hasCloudLinks(hasCloud)
                .hasPhysicalAttachments(hasPhysical)
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

        // Strip dangerous containers: <iframe>, <embed>, <object>, <base>, <link>, <applet>, <form>
        // Note: <meta> is allowed for responsiveness (Standard in GitHub/Newsletters)
        java.util.regex.Pattern framePattern = java.util.regex.Pattern.compile("(?is)<(iframe|embed|object|base|link|applet|form)\\b[^>]*>.*?</\\1>|<(iframe|embed|object|base|link|applet|form)\\b[^>]*>");
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
            // V12: Removed 'url\s*\(' from blacklist. Many modern emails use url() for safe fonts/images.
            // Still blocking dangerous expressions and JS-in-CSS.
            if (content.toLowerCase().matches(".*(expression|javascript|@import).*")) {
                styleStrippedCount++;
                styleMatcher.appendReplacement(styleSb, "");
            } else {
                styleMatcher.appendReplacement(styleSb, styleMatcher.group(0));
            }
        }

        styleMatcher.appendTail(styleSb);
        resolvedHtml = styleSb.toString();

        // 2. Browser standard compliance fix: Convert ';' to ',' in meta tags (Fixes console warnings)
        java.util.regex.Pattern metaSemicolonPattern = java.util.regex.Pattern.compile("(?is)<meta\\b[^>]*name=[\"']viewport[\"'][^>]*content=[\"']([^\"']+)[\"']");
        java.util.regex.Matcher metaMatcher = metaSemicolonPattern.matcher(resolvedHtml);
        StringBuffer metaSb = new StringBuffer();
        while (metaMatcher.find()) {
            String content = metaMatcher.group(1);
            if (content.contains(";")) {
                String fixedContent = content.replace(";", ",");
                metaMatcher.appendReplacement(metaSb, metaMatcher.group(0).replace(content, fixedContent));
            } else {
                metaMatcher.appendReplacement(metaSb, metaMatcher.group(0));
            }
        }
        metaMatcher.appendTail(metaSb);
        resolvedHtml = metaSb.toString();

        log.info("[V10-SHIELD-AUDIT] Email {}: Stripped {} scripts, {} frames/meta, {} on* events, {} js-urls, {} styles", 
                emailId, scriptCount, frameCount, onEventCount, jsUrlCount, styleStrippedCount);

        if (attachments == null || attachments.isEmpty()) {
            log.info("[V7-DEBUG-END] No attachments found for email {}", emailId);
            return resolvedHtml;
        }

        log.debug("[Rendering] Checking CID replacements for email ID: {}", emailId);
        
        for (EmailAttachment at : attachments) {
            // V12: Remove strict checking of at.isInline(). Many clients use cid: for regular attachments.
            if (at.getContentId() != null) {
                // Normalize Content-ID (strip brackets if present)
                String cid = at.getContentId().replaceAll("[<>]", "").trim();
                String proxyUrl = String.format("/api/v1/emails/%d/attachments/%d/inline", emailId, at.getId());
                
                // Case-Insensitive regex to find 'cid:[<]ID[>]'
                java.util.regex.Pattern cidPattern = java.util.regex.Pattern.compile("(?i)cid:<?(" + java.util.regex.Pattern.quote(cid) + ")>?");
                java.util.regex.Matcher cidMatcher = cidPattern.matcher(resolvedHtml);
                
                int count = 0;
                StringBuffer sb = new StringBuffer();
                while (cidMatcher.find()) {
                    cidMatcher.appendReplacement(sb, proxyUrl);
                    count++;
                }
                cidMatcher.appendTail(sb);
                
                if (count > 0) {
                    resolvedHtml = sb.toString();
                    log.info("[V12-CID] Replaced {} matches for CID: {} in Email ID: {}", count, cid, emailId);
                } else {
                    // Log even if zero matches to help debug why VNG emails aren't replacing
                    log.debug("[V12-CID] Checking CID: {} - No matches found in body for Email ID: {}", cid, emailId);
                }
            }
        }

        // Bridge Script for Secure Auto-Resize (V13 Optimization)
        String styleFix = "<style>" +
            "html, body { margin: 0 !important; padding: 0 !important; overflow-x: hidden !important; width: 100% !important; }" +
            "* { max-width: 100vw !important; box-sizing: border-box !important; }" +
            "</style>";

        String bridgeScript = styleFix + "<script>" +
            "function sendHeight() {" +
            "  var body = document.body, html = document.documentElement;" +
            "  var height = Math.max(body.scrollHeight, body.offsetHeight, html.clientHeight, html.scrollHeight, html.offsetHeight);" +
            "  // Use a slightly more conservative approach to avoid infinite expansion\n" +
            "  var finalHeight = html.offsetHeight || body.offsetHeight;" +
            "  window.parent.postMessage({ type: 'MB_RESIZE', height: finalHeight }, '*');" +
            "}" +
            "window.onload = sendHeight;" +
            "window.onresize = sendHeight;" +
            "// Periodic check for dynamic content (e.g. late loading images)\n" +
            "setInterval(sendHeight, 1500);" +
            "</script>";
        
        resolvedHtml = resolvedHtml + bridgeScript;


        return resolvedHtml;
    }
}
