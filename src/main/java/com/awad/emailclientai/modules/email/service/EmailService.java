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
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final EmailRepository emailRepository;
    private final EmailAttachmentRepository attachmentRepository;
    private final ImapService imapService;

    @PostConstruct
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
        Pattern scriptPattern = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
        Matcher scriptMatcher = scriptPattern.matcher(resolvedHtml);
        while (scriptMatcher.find()) scriptCount++;
        resolvedHtml = scriptMatcher.replaceAll("");

        // Strip dangerous containers: <iframe>, <embed>, <object>, <base>, <link>, <applet>, <form>
        // Note: <meta> is allowed for responsiveness (Standard in GitHub/Newsletters)
        Pattern framePattern = Pattern.compile("(?is)<(iframe|embed|object|base|link|applet|form)\\b[^>]*>.*?</\\1>|<(iframe|embed|object|base|link|applet|form)\\b[^>]*>");
        Matcher frameMatcher = framePattern.matcher(resolvedHtml);
        while (frameMatcher.find()) frameCount++;
        resolvedHtml = frameMatcher.replaceAll("");


        // Strip ALL 'on*' event attributes (aggressive purge)
        Pattern onEventPattern = Pattern.compile("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
        Matcher onMatcher = onEventPattern.matcher(resolvedHtml);
        while (onMatcher.find()) onEventCount++;
        resolvedHtml = onMatcher.replaceAll("");

        // Strip 'javascript:' URLs
        Pattern jsUrlPattern = Pattern.compile("(?i)(href|src)\\s*=\\s*(\"javascript:[^\"]*\"|'javascript:[^']*'|javascript:[^\\s>]+)");
        Matcher jsMatcher = jsUrlPattern.matcher(resolvedHtml);
        while (jsMatcher.find()) jsUrlCount++;
        resolvedHtml = jsMatcher.replaceAll("$1=\"#\"");

        // Sanitize <style> tags (remove blocks with expressions or imports)
        Pattern stylePattern = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
        Matcher styleMatcher = stylePattern.matcher(resolvedHtml);
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
        // High compatibility regex: finds <meta> regardless of attribute order
        Pattern metaPattern = Pattern.compile("(?is)<meta\\b([^>]*?)>");
        Matcher metaMatcher = metaPattern.matcher(resolvedHtml);
        StringBuffer metaSb = new StringBuffer();
        while (metaMatcher.find()) {
            String attributes = metaMatcher.group(1).toLowerCase();
            // Only target viewport meta tags
            if (attributes.contains("viewport") && attributes.contains("content=")) {
                Pattern contentPattern = Pattern.compile("(?i)content\\s*=\\s*[\"']([^\"']+)[\"']");
                Matcher contentMatcher = contentPattern.matcher(metaMatcher.group(0));
                if (contentMatcher.find()) {
                    String contentRaw = contentMatcher.group(1);
                    String fixedContent = contentRaw.replace(";", ",")
                                                 .replaceAll(",+", ",")
                                                 .replaceAll("^,+|,+$", "").trim();
                    metaMatcher.appendReplacement(metaSb, Matcher.quoteReplacement(
                            metaMatcher.group(0).replace(contentRaw, fixedContent)));
                    continue;
                }
            }
            metaMatcher.appendReplacement(metaSb, Matcher.quoteReplacement(metaMatcher.group(0)));
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
                Pattern cidPattern = Pattern.compile("(?i)cid:<?(" + Pattern.quote(cid) + ")>?");
                Matcher cidMatcher = cidPattern.matcher(resolvedHtml);
                
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
        // V16-V19 Stabilization: Wrap content in a safe container and neutralize all height loops
        resolvedHtml = "<div id=\"mb-stable-container\">" + resolvedHtml + "</div>";

        // Bridge Script for Secure Auto-Resize (V19 Reactive Stabilization)
        String styleFix = "<style>" +
            "html, body { margin: 0 !important; padding: 0 !important; overflow-y: hidden !important; overflow-x: auto !important; width: 100% !important; height: auto !important; min-height: 100% !important; }" +
            "#mb-stable-container { display: flow-root !important; width: 100% !important; height: auto !important; min-height: 0 !important; }" +
            "img { max-width: 100% !important; height: auto !important; }" + 
            "</style>";

        String bridgeScript = styleFix + "<script>" +
            "(function() {" +
            "  var lastHeight = 0;" +
            "  // V20: Immediate reset-to-baseline (400px) to match frontend reset logic\n" +
            "  window.parent.postMessage({ type: 'MB_RESIZE', height: 400 }, '*');" +
            "  " +
            "  function sendHeight() {" +
            "    var container = document.getElementById('mb-stable-container');" +
            "    if (!container) return;" +
            "    var newHeight = container.offsetHeight;" +
            "    if (newHeight > 10 && Math.abs(newHeight - lastHeight) > 3) {" +
            "      lastHeight = newHeight;" +
            "      window.parent.postMessage({ type: 'MB_RESIZE', height: newHeight + 40 }, '*');" +
            "    }" +
            "  }" +
            "  " +
            "  // V19: Use ResizeObserver for instant, efficient reactiveness\n" +
            "  if (window.ResizeObserver) {" +
            "    var observer = new ResizeObserver(sendHeight);" +
            "    observer.observe(document.getElementById('mb-stable-container'));" +
            "  } else {" +
            "    setInterval(sendHeight, 1500);" + // Fallback for very old browsers
            "  }" +
            "  window.onload = function() { setTimeout(sendHeight, 200); };" +
            "  window.onresize = sendHeight;" +
            "  document.addEventListener('DOMContentLoaded', sendHeight);" +
            "})();" +
            "</script>";
        
        resolvedHtml = resolvedHtml + bridgeScript;

        return resolvedHtml;
    }
}



