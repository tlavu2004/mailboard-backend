package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.ContactDto;
import com.awad.emailclientai.modules.email.dto.response.EmailEntityDto;
import com.awad.emailclientai.modules.email.entity.EmailAttachment;
import com.awad.emailclientai.modules.email.entity.EmailEntity;
import com.awad.emailclientai.modules.email.entity.EmailStatus;
import com.awad.emailclientai.modules.email.repository.EmailAttachmentRepository;
import com.awad.emailclientai.modules.email.repository.EmailRepository;
import com.awad.emailclientai.modules.email.repository.EmailSenderRepository;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;

import jakarta.mail.MessagingException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final EmailRepository emailRepository;
    private final EmailAttachmentRepository attachmentRepository;
    private final ImapService imapService;
    private final EmailSyncService emailSyncService;
    private final GmailLabelService gmailLabelService;
    private final EmailSenderRepository emailSenderRepository;
    private final EmailAccountRepository emailAccountRepository;
    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        log.info(">>>> Initialized and Monitoring Rendering <<<<");
    }

    @Transactional
    public EmailEntityDto getEmailDetail(Long id) {
        EmailEntity email = emailRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
        
        // V30: Live Healing - If body is completely missing, reach out to IMAP to recover it
        if (email.getBody() == null || email.getBody().trim().isEmpty()) {
            log.info("[LIVE-HEALING] Body missing for email ID: {}. Attempting recovery from server...", id);
            try {
                // Determine folder name (Simplified: LinkedIn/Standard incoming is almost always INBOX)
                String folderName = "INBOX";
                if ("SENT".equalsIgnoreCase(email.getStatus())) {
                    folderName = "SENT";
                }
                
                String liveBody = imapService.fetchLiveBody(email.getAccount(), folderName, email.getUid());
                
                if (liveBody != null && !liveBody.isEmpty()) {
                    email.setBody(liveBody);
                    
                    // Also heal snippet if it was just the subject before
                    if (email.getSnippet() == null || email.getSnippet().trim().isEmpty() || email.getSnippet().equals(email.getSubject())) {
                        String cleanSnippet = imapService.stripHtml(liveBody);
                        email.setSnippet(cleanSnippet.length() > 200 ? cleanSnippet.substring(0, 197) + "..." : cleanSnippet);
                    }
                    
                    emailRepository.save(email);
                    log.info("[LIVE-HEALING] Success! Body recovered and persisted for email ID: {}", id);
                } else {
                    log.warn("[LIVE-HEALING] Server returned empty body for email ID: {}", id);
                }
            } catch (Exception e) {
                log.error("[LIVE-HEALING] Error during recovery for email ID: {}: {}", id, e.getMessage());
            }
        }

        // If backend marks email as having attachments but DB record has none,
        // attempt a targeted refresh to fetch attachment metadata from the server.
        if (email.isHasAttachments() && (email.getAttachments() == null || email.getAttachments().isEmpty())) {
            log.info("[LIVE-HEALING] Attachments missing for email ID: {}. Attempting detail refresh...", id);
            try {
                emailSyncService.refreshEmail(id);
                // Reload entity after refresh
                email = emailRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND));
            } catch (Exception e) {
                log.warn("[LIVE-HEALING] Failed to refresh attachments for email ID {}: {}", id, e.getMessage());
            }
        }
        
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
                        // Edge Case: If the image filename is explicitly referenced in the HTML body 
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

        // Dynamic Discovery Fallback for existing emails
        imapService.scanForCloudLinksEntityDto(body, attachments, new int[]{attachments.size()});

        // Final flag calculation for accurate UI indicators
        boolean hasCloud = attachments.stream().anyMatch(a -> a.getExternalUrl() != null);
        boolean hasPhysical = attachments.stream().anyMatch(a -> a.getExternalUrl() == null);

        // V41: Universal Account Branding & Aggressive Discovery
        String rawSender = entity.getSender();
        String senderEmail = cleanEmail(rawSender);
        String currentName = entity.getFromName();
        Long userId = (entity.getAccount() != null && entity.getAccount().getUser() != null) ? entity.getAccount().getUser().getId() : null;
        com.awad.emailclientai.modules.email.entity.EmailAccount account = entity.getAccount();
        
        // 1. Priority: If this sender matches ANY of the user's connected accounts or main profile, use "You" logic
        boolean fromMe = false;
        if (senderEmail != null && account != null && account.getUser() != null) {
            List<EmailAccount> userAccounts = emailAccountRepository.findByUserId(userId);
            
            String primaryEmail = entity.getAccount().getUser().getEmail();
            boolean isUserAccount = userAccounts.stream()
                .anyMatch(acc -> senderEmail.equalsIgnoreCase(acc.getEmailAddress()));
            
            if (isUserAccount || senderEmail.equalsIgnoreCase(primaryEmail)) {
                fromMe = true;
                currentName = entity.getAccount().getDisplayName();
                if (currentName == null || currentName.isBlank() || currentName.equalsIgnoreCase(senderEmail)) {
                    currentName = entity.getAccount().getUser().getName();
                }
            }
        }

        boolean nameIsRaw = currentName == null || currentName.isBlank() || currentName.equalsIgnoreCase(senderEmail) || currentName.contains("@");
        
        // V41: Self-Healing - If name is missing/raw, try to extract from rawSender "Name <email>"
        if (nameIsRaw && rawSender != null && rawSender.contains("<") && rawSender.contains(">")) {
            int start = rawSender.indexOf("<");
            String extracted = rawSender.substring(0, start).trim();
            extracted = extracted.replaceAll("^\"|\"$", "").trim();
            String username = senderEmail != null && senderEmail.contains("@") ? senderEmail.split("@")[0] : "";
            
            if (!extracted.isEmpty() && !extracted.equalsIgnoreCase(senderEmail) && !extracted.equalsIgnoreCase(username) && !extracted.contains("@")) {
                currentName = extracted;
                nameIsRaw = false;
            }
        }

        if (nameIsRaw && senderEmail != null) {
            // 1. Try Smart Directory (Fast)
            currentName = emailSenderRepository.findByEmail(senderEmail.toLowerCase())
                .map(com.awad.emailclientai.modules.email.entity.EmailSender::getBestKnownName)
                .orElse(currentName);
                
            // 2. Fallback: Search DB for ANY other email from this sender that HAS a real name
            if (currentName == null || currentName.equalsIgnoreCase(senderEmail) || currentName.contains("@") || currentName.equalsIgnoreCase(senderEmail.split("@")[0])) {
                List<String> names = emailRepository.findDistinctFromNamesBySender(senderEmail.trim());
                String bestFound = null;
                for (String n : names) {
                    if (n != null && !n.isBlank()) {
                        String cleanedN = n;
                        if (cleanedN.contains("<") && cleanedN.contains(">")) {
                            cleanedN = cleanedN.substring(0, cleanedN.indexOf("<")).trim();
                        }
                        cleanedN = cleanedN.replaceAll("^\"|\"$", "").trim();
                        
                        String username = senderEmail.split("@")[0];
                        boolean isRealName = !cleanedN.isBlank() 
                            && !cleanedN.equalsIgnoreCase(senderEmail.trim()) 
                            && !cleanedN.equalsIgnoreCase(username)
                            && !cleanedN.contains("@");
                        
                        if (isRealName) {
                            // Prioritize names with spaces (likely Full Names)
                            if (cleanedN.contains(" ")) {
                                bestFound = cleanedN;
                                break; 
                            }
                            bestFound = cleanedN;
                        }
                    }
                }
                if (bestFound != null) currentName = bestFound;
            }
        }
        final String bestName = currentName;

        return EmailEntityDto.builder()
                .id(entity.getId())
                .messageId(entity.getMessageId())
                .threadId(entity.getThreadId())
                .gmailMessageId(entity.getGmailMessageId())
                .gmailDraftId(entity.getGmailDraftId())
                .uid(entity.getUid())
                .subject(entity.getSubject())
                .from(EmailEntityDto.EmailAddressDto.builder()
                        .name(bestName)
                        .email(senderEmail)
                        .build())
                .to(parseRecipientString(entity.getRecipientTo(), account))
                .cc(parseRecipientString(entity.getRecipientCc(), account))
                .sender(senderEmail) 
                .fromName(bestName) 
                .recipientTo(entity.getRecipientTo() != null ? java.util.Arrays.asList(entity.getRecipientTo().split(",\\s*")) : java.util.Collections.emptyList()) 
                .recipientCc(entity.getRecipientCc() != null ? java.util.Arrays.asList(entity.getRecipientCc().split(",\\s*")) : java.util.Collections.emptyList()) 
                .snippet(entity.getSnippet())
                .preview(entity.getSnippet())
                .body(body)
                .status(entity.getStatus())
                .mailboxId(entity.getStatus() != null ? entity.getStatus() : "INBOX")
                .receivedDate(entity.getReceivedDate())
                .receivedAt(entity.getReceivedDate() != null ? entity.getReceivedDate().atZone(ZoneId.systemDefault()).toInstant().toString() : null)
                .snoozedUntil(entity.getSnoozedUntil())
                .summary(entity.getSummary())
                .summarySource(entity.getSummarySource() != null ? entity.getSummarySource().name() : null)
                .isRead(entity.isRead())
                .isStarred(entity.isStarred())
                .isFromMe(fromMe)
                .hasAttachments(hasCloud || hasPhysical)
                .hasCloudLinks(hasCloud)
                .hasPhysicalAttachments(hasPhysical)
                .accountEmail(entity.getAccount().getEmailAddress())
                .attachments(attachments)
                .gmailDraftId(entity.getGmailDraftId())
                .deletedAt(entity.getDeletedAt())
                .gmailLink(entity.getGmailMessageId() != null ?
                    // Use u/0 plus authuser param to allow Gmail to open the correct signed-in account
                    String.format("https://mail.google.com/mail/u/0/?authuser=%s#inbox/%s",
                        URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                        entity.getGmailMessageId()) :
                    String.format("https://mail.google.com/mail/u/0/?authuser=%s#search/rfc822msgid:%s",
                        URLEncoder.encode(entity.getAccount().getEmailAddress(), StandardCharsets.UTF_8),
                        URLEncoder.encode(entity.getMessageId(), StandardCharsets.UTF_8)))
                .build();
    }

    private java.util.List<EmailEntityDto.EmailAddressDto> parseRecipientString(String recipientString, com.awad.emailclientai.modules.email.entity.EmailAccount account) {
        Long userId = account != null && account.getUser() != null ? account.getUser().getId() : null;
        if (recipientString == null || recipientString.isBlank()) {
            return java.util.Collections.emptyList();
        }
        return java.util.Arrays.stream(recipientString.split(",\\s*"))
                .map(part -> {
                    String name = "";
                    String email = part.trim();

                    // V41: Robust parsing using split or index
                    if (email.contains("<") && email.contains(">")) {
                        int start = email.indexOf("<");
                        int end = email.indexOf(">");
                        name = email.substring(0, start).trim();
                        email = email.substring(start + 1, end).trim();
                    }
                    
                    // Clean quotes from name and email (Java-compliant)
                    name = name.replaceAll("^\"|\"$", "").trim();
                    email = email.replaceAll("^\"|\"$", "").trim();
                    
                    // V42: Aggressive Recipient Name Discovery
                    String finalName = name;
                    String finalEmail = email;

                    // 1. Check EmailSender table
                    if (finalName.isBlank() || finalName.equalsIgnoreCase(finalEmail) || finalName.contains("@")) {
                        finalName = emailSenderRepository.findByEmail(finalEmail.toLowerCase())
                                .map(com.awad.emailclientai.modules.email.entity.EmailSender::getBestKnownName)
                                .orElse(finalName);
                    }

                    // 2. Check EmailAccount table (User's other accounts)
                    String localToMatch = finalEmail.contains("@") ? finalEmail.split("@")[0].toLowerCase().trim() : "";
                    
                    if (finalName.isBlank() || finalName.equalsIgnoreCase(finalEmail) || finalName.equals("You")) {
                        if (userId != null) {
                            String emailToMatch = finalEmail.toLowerCase().trim();
                            List<EmailAccount> userAccs = emailAccountRepository.findByUserId(userId);
                            
                            // Exact Match
                            Optional<EmailAccount> matchedAcc = userAccs.stream()
                                    .filter(acc -> acc.getEmailAddress().equalsIgnoreCase(emailToMatch))
                                    .findFirst();
                            
                            if (matchedAcc.isPresent()) {
                                finalName = matchedAcc.get().getDisplayName();
                            } 
                            // Fuzzy Match by local part (for multi-provider accounts)
                            else if (localToMatch.length() > 3) {
                                finalName = userAccs.stream()
                                        .filter(acc -> {
                                            String alp = acc.getEmailAddress().split("@")[0].toLowerCase();
                                            boolean prefixMatch = localToMatch.length() > 10 && alp.length() > 10 && 
                                                                 (localToMatch.startsWith(alp.substring(0, 10)) || alp.startsWith(localToMatch.substring(0, 10)));
                                            return alp.equalsIgnoreCase(localToMatch) || prefixMatch;
                                        })
                                        .map(EmailAccount::getDisplayName)
                                        .filter(n -> n != null && !n.isBlank())
                                        .findFirst()
                                        .orElse(finalName);
                            }
                        }
                    }
                    
                    // 3. Last Resort: User profile name fallback
                    if ((finalName.isBlank() || finalName.equalsIgnoreCase(finalEmail) || finalName.equals("You")) && account != null) {
                        com.awad.emailclientai.modules.user.entity.User u = account.getUser();
                        if (u != null) {
                            String userPrimaryEmail = u.getEmail().toLowerCase().trim();
                            if (finalEmail.equalsIgnoreCase(userPrimaryEmail) || (localToMatch.length() > 3 && userPrimaryEmail.contains("@") && userPrimaryEmail.split("@")[0].equalsIgnoreCase(localToMatch))) {
                                finalName = u.getName();
                            }
                        }
                    }

                    if (log.isDebugEnabled() && !finalName.equalsIgnoreCase(name)) {
                        log.debug("[RECIPIENT-DISCOVERY] Discovered name '{}' for email '{}' (User ID: {})", 
                                finalName, finalEmail, userId);
                    }

                    return EmailEntityDto.EmailAddressDto.builder()
                            .name(finalName.isBlank() || finalName.equalsIgnoreCase(finalEmail) ? null : finalName)
                            .email(finalEmail)
                            .build();
                })
                .collect(java.util.stream.Collectors.toList());
    }

    public String processEmailBody(String html, Long emailId, List<EmailAttachment> attachments) {
        if (html == null) return null;
        
        log.info("[DEBUG-START] Processing Email ID: {}", emailId);
        
        String resolvedHtml = html;

        // V31: Plain-text detection for legacy DB records that were stored before the ImapService fix.
        // If the body contains no HTML tags at all (e.g. GitHub text-only notifications),
        // convert it to proper HTML with line breaks and clickable links.
        if (!resolvedHtml.contains("<") && !resolvedHtml.contains(">")) {
            log.info("[PLAIN-TEXT-FIX] Email {} body has no HTML tags. Converting plain text to HTML.", emailId);
            resolvedHtml = convertPlainTextToHtml(resolvedHtml);
        }
        // Also catch bodies that only have the mb-plain-text-body wrapper but no <br> inside
        // (legacy records from the old wrapping logic)
        else if (resolvedHtml.startsWith("<div class=\"mb-plain-text-body\">") && !resolvedHtml.contains("<br>") && !resolvedHtml.contains("<a ")) {
            log.info("[PLAIN-TEXT-FIX] Email {} has legacy plain-text wrapper without formatting. Re-converting.", emailId);
            // Strip the old wrapper and re-process
            String inner = resolvedHtml.replace("<div class=\"mb-plain-text-body\">", "").replace("</div>", "");
            resolvedHtml = convertPlainTextToHtml(inner);
        }

        int scriptCount = 0;
        int onEventCount = 0;
        int jsUrlCount = 0;
        int frameCount = 0;
        int styleStrippedCount = 0;

        // 1. HTML Sanitization
        
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
            // Removed 'url\s*\(' from blacklist. Many modern emails use url() for safe fonts/images.
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


        log.info("[SHIELD-AUDIT] Email {}: Stripped {} scripts, {} frames/meta, {} on* events, {} js-urls, {} styles", 
                emailId, scriptCount, frameCount, onEventCount, jsUrlCount, styleStrippedCount);

        if (attachments == null || attachments.isEmpty()) {
            log.info("[DEBUG-END] No attachments found for email {}", emailId);
            return resolvedHtml;
        }

        log.debug("[Rendering] Checking CID replacements for email ID: {}", emailId);
        
        for (EmailAttachment at : attachments) {
            // Remove strict checking of at.isInline(). Many clients use cid: for regular attachments.
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
                    log.info("[CID] Replaced {} matches for CID: {} in Email ID: {}", count, cid, emailId);
                } else {
                    // Log even if zero matches to help debug why VNG emails aren't replacing
                    log.debug("[CID] Checking CID: {} - No matches found in body for Email ID: {}", cid, emailId);
                }
            }
        }
        // Final styling and script injection
        resolvedHtml = injectPremiumExperience(resolvedHtml, emailId);

        return resolvedHtml;
    }

    private String injectPremiumExperience(String html, Long emailId) {
        if (html == null) return null;

        // Scope visual fixes to the container to avoid colliding with email templates' global CSS
        String styleFix = "<style>" +
            "#mb-stable-container { " +
            "  box-sizing: border-box !important; padding: 12px !important; " +
            "  width: 100% !important; height: auto !important; min-height: 0 !important; " +
            "  background: transparent !important; color: inherit !important; " +
            "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif !important; " +
            "  line-height: 1.5 !important; " +
            "}" +
            ".mb-plain-text-body { white-space: pre-wrap !important; word-wrap: break-word !important; overflow-wrap: break-word !important; }" +
            "#mb-stable-container img { max-width: 100% !important; height: auto !important; display: inline-block; vertical-align: middle; }" +
            "#mb-stable-container a { color: #3182ce !important; text-decoration: underline !important; }" +
            // Target common dark-mode helpers used by edX/Coursera templates. STRICTLY scoped to our container only.
            "#mb-stable-container .darkmode, #mb-stable-container .darkmode *, #mb-stable-container .darkmode2, #mb-stable-container .darkmode2 *, #mb-stable-container .darkmode3, #mb-stable-container .darkmode3 * { background-color: #ffffff !important; color: #000000 !important; background-image: none !important; filter: none !important; mix-blend-mode: normal !important; -webkit-text-fill-color: #000000 !important; }" +
            "</style>";

        String bridgeScriptOnly = "<script>" +
            "(function() {" +
            "  var lastHeight = 0;" +
            "  window.parent.postMessage({ type: 'MB_RESIZE', height: 400 }, '*');" +
            "  function sendHeight() {" +
            "    var frame = document.getElementById('mb-stable-container');" +
            "    if (frame) {" +
            "      var newHeight = frame.offsetHeight + 24;" +
            "      if (Math.abs(newHeight - lastHeight) > 5) {" +
            "        lastHeight = newHeight;" +
            "        window.parent.postMessage({ type: 'MB_RESIZE', height: newHeight }, '*');" +
            "      }" +
            "    }" +
            "  }" +
            "  if (window.ResizeObserver) {" +
            "    var observer = new ResizeObserver(sendHeight);" +
            "    observer.observe(document.getElementById('mb-stable-container'));" +
            "  } else {" +
            "    setInterval(sendHeight, 1000);" +
            "    window.onload = sendHeight;" +
            "  }" +
            "})();" +
            "</script>";

        String lower = html.toLowerCase();
        boolean isFullDoc = lower.contains("<html") || lower.contains("<!doctype") || lower.contains("<body");

        if (isFullDoc) {
            // Insert styleFix into head when possible to avoid breaking document structure
            if (lower.contains("</head>")) {
                int idx = lower.indexOf("</head>");
                html = html.substring(0, idx) + styleFix + html.substring(idx);
            } else if (lower.contains("<head")) {
                int headOpen = lower.indexOf("<head");
                int headClose = html.indexOf('>', headOpen);
                if (headClose != -1) {
                    int insertPos = headClose + 1;
                    html = html.substring(0, insertPos) + styleFix + html.substring(insertPos);
                } else {
                    // fallback: inject a head after <html>
                    if (lower.contains("<html")) {
                        int htmlOpen = lower.indexOf("<html");
                        int htmlClose = html.indexOf('>', htmlOpen);
                        if (htmlClose != -1) {
                            int insertPos = htmlClose + 1;
                            html = html.substring(0, insertPos) + "<head>" + styleFix + "</head>" + html.substring(insertPos);
                        }
                    }
                }
            } else if (lower.contains("<html")) {
                int htmlOpen = lower.indexOf("<html");
                int htmlClose = html.indexOf('>', htmlOpen);
                if (htmlClose != -1) {
                    int insertPos = htmlClose + 1;
                    html = html.substring(0, insertPos) + "<head>" + styleFix + "</head>" + html.substring(insertPos);
                }
            } else {
                // Can't reasonably inject into head; fall back to wrapping
                String processedHtml = "<div id=\"mb-stable-container\">" + html + "</div>";
                return processedHtml + styleFix + bridgeScriptOnly;
            }

            // Insert bridge script before </body> or append
            if (lower.contains("</body>")) {
                int idx2 = lower.indexOf("</body>");
                html = html.substring(0, idx2) + bridgeScriptOnly + html.substring(idx2);
            } else {
                html = html + bridgeScriptOnly;
            }

            return html;
        }

        // Fragment: safe to wrap inside our container
        String processedHtml = "<div id=\"mb-stable-container\">" + html + "</div>";
        return processedHtml + styleFix + bridgeScriptOnly;
    }

    /**
     * Converts raw plain-text to presentable HTML with line breaks and clickable links.
     * Used for legacy DB records and text-only emails (e.g. GitHub notifications).
     */
    private String convertPlainTextToHtml(String plainText) {
        // Step 1: HTML-escape to prevent XSS
        String escaped = plainText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");

        // Step 2: Auto-link URLs
        Pattern urlPattern = Pattern.compile("(https?://[^\\s&]+)");
        Matcher urlMatcher = urlPattern.matcher(escaped);
        StringBuffer sb = new StringBuffer();
        while (urlMatcher.find()) {
            String url = urlMatcher.group(1);
            String trimmed = url.replaceAll("[.,;:!?)]+$", "");
            String trailing = url.substring(trimmed.length());
            String replacement = "<a href=\"" + trimmed + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + trimmed + "</a>" + trailing;
            urlMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        urlMatcher.appendTail(sb);
        escaped = sb.toString();

        // Step 3: Convert newlines to <br> tags
        escaped = escaped.replace("\r\n", "<br>").replace("\n", "<br>");

        return "<div class=\"mb-plain-text-body\">" + escaped + "</div>";
    }

    @Async
    @Transactional
    public void syncStatusToProvider(Long emailId, String previousStatus, String newStatus) {
        EmailEntity email = emailRepository.findById(emailId).orElse(null);
        if (email == null) {
            log.warn("Async sync failed: Email {} not found", emailId);
            return;
        }
        
        if (previousStatus == null) previousStatus = "INBOX";
        if (newStatus == null) newStatus = "INBOX";
        
        String normalizedPrev = previousStatus.toUpperCase(java.util.Locale.ROOT);
        String normalizedNew = newStatus.toUpperCase(java.util.Locale.ROOT);
        
        if (normalizedPrev.equals(normalizedNew)) return;
        
        try {
            boolean isGmail = email.getAccount().getProvider() == com.awad.emailclientai.modules.email.entity.EmailProvider.GMAIL;
            String sourceFolder = resolveFolderForStatus(normalizedPrev, email.getAccount().getProvider());
            
            if (normalizedNew.equals("TRASH")) {
                if (isGmail) {
                    gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("TRASH"), List.of("INBOX", "SPAM"));
                } else {
                    String trashFolder = resolveFolderForStatus("TRASH", email.getAccount().getProvider());
                    imapService.moveMessageByMessageId(email.getAccount(), sourceFolder, trashFolder, email.getMessageId());
                }
            } else if (normalizedNew.equals("SPAM")) {
                if (isGmail) {
                    gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("SPAM"), List.of("INBOX", "TRASH"));
                } else {
                    String spamFolder = resolveFolderForStatus("SPAM", email.getAccount().getProvider());
                    imapService.moveMessageByMessageId(email.getAccount(), sourceFolder, spamFolder, email.getMessageId());
                }
            } else {
                // Moving to INBOX or a custom Kanban status (e.g. IN_PROGRESS)
                // We MUST untrash/unspam it on Gmail/IMAP if it was in TRASH or SPAM
                if (normalizedPrev.equals("TRASH")) {
                    if (isGmail) {
                        gmailLabelService.untrashMessage(email.getAccount(), email.getMessageId(), email.getGmailMessageId());
                        gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("INBOX"), List.of("TRASH", "SPAM"));
                    } else {
                        String trashFolder = resolveFolderForStatus("TRASH", email.getAccount().getProvider());
                        imapService.moveMessageByMessageId(email.getAccount(), trashFolder, "INBOX", email.getMessageId());
                    }
                } else if (normalizedPrev.equals("SPAM")) {
                    if (isGmail) {
                        gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("INBOX"), List.of("SPAM", "TRASH"));
                    } else {
                        String spamFolder = resolveFolderForStatus("SPAM", email.getAccount().getProvider());
                        imapService.moveMessageByMessageId(email.getAccount(), spamFolder, "INBOX", email.getMessageId());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to sync status {} to {} for email {}: {}", previousStatus, newStatus, email.getUid(), e.getMessage());
        }
    }

    @org.springframework.scheduling.annotation.Async
    @Transactional
    public void syncFlagsAndLabelsToProvider(Long emailId, String previousStatus, List<String> normalizedAdd, List<String> normalizedRemove) {
        log.info("[SYNC-START] Syncing flags for email {}: previousStatus={}, add={}, remove={}", emailId, previousStatus, normalizedAdd, normalizedRemove);
        EmailEntity email = emailRepository.findById(emailId).orElse(null);
        if (email == null) {
            log.warn("Async sync failed: Email {} not found", emailId);
            return;
        }
        
        try {
            boolean isGmail = email.getAccount().getProvider() == com.awad.emailclientai.modules.email.entity.EmailProvider.GMAIL;
            String sourceFolder = resolveFolderForStatus(previousStatus, email.getAccount().getProvider());

            if (isGmail) {
                List<String> gmailAdd = new ArrayList<>();
                List<String> gmailRemove = new ArrayList<>();
                
                if (normalizedRemove.contains("UNREAD")) gmailRemove.add("UNREAD");
                if (normalizedAdd.contains("UNREAD")) gmailAdd.add("UNREAD");
                if (normalizedAdd.contains("STARRED")) gmailAdd.add("STARRED");
                if (normalizedRemove.contains("STARRED")) gmailRemove.add("STARRED");
                
                if (!gmailAdd.isEmpty() || !gmailRemove.isEmpty()) {
                    gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), gmailAdd, gmailRemove);
                    log.info("Synced flags to Gmail via API: add={}, remove={}", gmailAdd, gmailRemove);
                }
            } else {
                if (normalizedRemove.contains("UNREAD")) {
                    imapService.setMessageRead(email.getAccount(), sourceFolder, email.getUid(), true);
                } else if (normalizedAdd.contains("UNREAD")) {
                    imapService.setMessageRead(email.getAccount(), sourceFolder, email.getUid(), false);
                }

                if (normalizedAdd.contains("STARRED")) {
                    imapService.setMessageStarred(email.getAccount(), sourceFolder, email.getUid(), true);
                } else if (normalizedRemove.contains("STARRED")) {
                    imapService.setMessageStarred(email.getAccount(), sourceFolder, email.getUid(), false);
                }
            }

            if (normalizedAdd.contains("TRASH")) {
                if (isGmail) {
                    gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("TRASH"), List.of("INBOX", "SPAM"));
                } else {
                    String trashFolder = resolveFolderForStatus("TRASH", email.getAccount().getProvider());
                    imapService.moveMessageByMessageId(email.getAccount(), sourceFolder, trashFolder, email.getMessageId());
                }
                log.info("Successfully trashed email (UID: {}) from Gmail", email.getUid());
            } else if (normalizedAdd.contains("SPAM")) {
                if (isGmail) {
                    gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), List.of("SPAM"), List.of("INBOX", "TRASH"));
                } else {
                    String spamFolder = resolveFolderForStatus("SPAM", email.getAccount().getProvider());
                    imapService.moveMessageByMessageId(email.getAccount(), sourceFolder, spamFolder, email.getMessageId());
                }
                log.info("Successfully moved email (UID: {}) to SPAM", email.getUid());
            } else if (normalizedAdd.contains("INBOX") && (normalizedRemove.contains("TRASH") || normalizedRemove.contains("SPAM"))) {
                String currentStatus = email.getStatus();
                boolean isDraftOrSent = "DRAFTS".equalsIgnoreCase(currentStatus) || "DRAFT".equalsIgnoreCase(currentStatus) || "SENT".equalsIgnoreCase(currentStatus);
                
                if (isGmail) {
                    if (normalizedRemove.contains("TRASH")) {
                        gmailLabelService.untrashMessage(email.getAccount(), email.getMessageId(), email.getGmailMessageId());
                    }
                    
                    List<String> labelsToAdd = new ArrayList<>();
                    List<String> labelsToRemove = new ArrayList<>(normalizedRemove);
                    
                    if (!isDraftOrSent) {
                        // If it's a custom status (Kanban), add its label. Otherwise add INBOX.
                        if (!EmailStatus.INBOX.equalsIgnoreCase(currentStatus)) {
                            labelsToAdd.add(currentStatus);
                        } else {
                            labelsToAdd.add("INBOX");
                        }
                    }
                    
                    if (!labelsToAdd.isEmpty() || !labelsToRemove.isEmpty()) {
                        gmailLabelService.modifyMessageLabels(email.getAccount(), email.getMessageId(), email.getGmailMessageId(), labelsToAdd, labelsToRemove);
                    }
                } else {
                    String fromFolder = normalizedRemove.contains("TRASH") ? resolveFolderForStatus("TRASH", email.getAccount().getProvider()) : resolveFolderForStatus("SPAM", email.getAccount().getProvider());
                    String targetFolder = resolveFolderForStatus(currentStatus, email.getAccount().getProvider());
                    imapService.moveMessageByMessageId(email.getAccount(), fromFolder, targetFolder, email.getMessageId());
                }
                log.info("Successfully restored email (UID: {}) to {}", email.getUid(), currentStatus);
            }
        } catch (Exception e) {
            log.error("Failed to sync flags/deletion to Gmail for email {}: {}", email.getUid(), e.getMessage());
        }

        // Notify frontend
        notifyUpdated(email.getAccount().getId(), emailId);
    }

    private void notifyUpdated(Long accountId, Long emailId) {
        try {
            Map<String, Object> payload = Map.of(
                "type", "UPDATED_EMAILS",
                "emailIds", List.of(emailId)
            );
            notificationWebSocketHandler.sendRawNotification(accountId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to send WebSocket notification for email {}: {}", emailId, e.getMessage());
        }
    }

    private void notifyDeleted(Long accountId, Long emailId) {
        try {
            Map<String, Object> payload = Map.of(
                "type", "DELETED_EMAILS",
                "emailIds", List.of(emailId)
            );
            notificationWebSocketHandler.sendRawNotification(accountId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to send WebSocket deletion notification for email {}: {}", emailId, e.getMessage());
        }
    }

    private String resolveFolderForStatus(String status, com.awad.emailclientai.modules.email.entity.EmailProvider provider) {
        String normalized = status == null ? "INBOX" : status.toUpperCase(java.util.Locale.ROOT);
        if (provider == com.awad.emailclientai.modules.email.entity.EmailProvider.GMAIL) {
            return switch (normalized) {
                case "SPAM" -> "[Gmail]/Spam";
                case "TRASH" -> "[Gmail]/Trash";
                case "SENT" -> "[Gmail]/Sent Mail";
                case "DRAFT", "DRAFTS" -> "[Gmail]/Drafts";
                case "STARRED" -> "INBOX";
                default -> status != null ? status : "INBOX";
            };
        }

        return switch (normalized) {
            case "SPAM" -> "Spam";
            case "TRASH" -> "Trash";
            case "SENT" -> "Sent";
            case "DRAFT", "DRAFTS" -> "Drafts";
            default -> status != null ? status : "INBOX";
        };
    }
    private String cleanEmail(String input) {
        if (input == null) return null;
        if (input.contains("<") && input.contains(">")) {
            int start = input.indexOf("<");
            int end = input.indexOf(">");
            if (start < end) {
                return input.substring(start + 1, end).trim();
            }
        }
        return input.trim();
    }

    @Async
    @Transactional
    public void emptyTrash(EmailAccount account) {
        List<EmailEntity> trashEmails = emailRepository.findAllByAccountIdAndStatus(account.getId(), "TRASH");
        log.info("[EMPTY-TRASH] Found {} emails in TRASH for account {}", trashEmails.size(), account.getEmailAddress());
        if (trashEmails.isEmpty()) return;

        try {
            boolean isGmail = account.getProvider() == com.awad.emailclientai.modules.email.entity.EmailProvider.GMAIL;
            if (isGmail) {
                log.info("[EMPTY-TRASH] Bulk deleting {} Gmail messages for account {}", trashEmails.size(), account.getEmailAddress());
                for (EmailEntity email : trashEmails) {
                    if (email.getGmailMessageId() != null) {
                        try {
                            gmailLabelService.deleteMessage(account, email.getMessageId(), email.getGmailMessageId());
                            // After successful provider delete, we can safely delete from local DB
                            Long deletedId = email.getId();
                            emailRepository.delete(email);
                            notifyDeleted(account.getId(), deletedId);
                        } catch (Exception e) {
                            log.warn("Failed to delete message {} from Gmail Trash: {}", email.getGmailMessageId(), e.getMessage());
                        }
                    } else {
                        // If no gmail ID but in TRASH, just delete locally to clean up
                        Long deletedId = email.getId();
                        emailRepository.delete(email);
                        notifyDeleted(account.getId(), deletedId);
                    }
                }
            } else {
                // For IMAP, we need to mark as deleted and expunge
                String trashFolder = resolveFolderForStatus("TRASH", account.getProvider());
                for (EmailEntity email : trashEmails) {
                    try {
                        imapService.deleteMessage(account, trashFolder, email.getUid());
                    } catch (Exception e) {
                        log.warn("Failed to delete message {} from IMAP Trash: {}", email.getUid(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error clearing provider trash for account {}: {}", account.getEmailAddress(), e.getMessage());
        }

        // Always clear from local DB
        emailRepository.deleteAll(trashEmails);
        log.info("Emptied TRASH for account {}: removed {} records", account.getEmailAddress(), trashEmails.size());
    }

    @Transactional
    public void deleteEmailPermanently(Long emailId) {
        EmailEntity email = emailRepository.findById(emailId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_FOUND, "Email not found"));
        EmailAccount account = email.getAccount();

        try {
            boolean isGmail = account.getProvider() == com.awad.emailclientai.modules.email.entity.EmailProvider.GMAIL;
            if (isGmail) {
                if (email.getGmailMessageId() != null) {
                    gmailLabelService.deleteMessage(account, email.getMessageId(), email.getGmailMessageId());
                } else if (email.getGmailDraftId() != null) {
                    gmailLabelService.deleteDraft(account, email.getGmailDraftId());
                }
            } else {
                String folder = resolveFolderForStatus(email.getStatus(), account.getProvider());
                imapService.deleteMessage(account, folder, email.getUid());
            }
            // Only delete locally if provider delete succeeds (or no provider ID)
            emailRepository.delete(email);
            notifyDeleted(account.getId(), emailId);
            log.info("Permanently deleted email {} from local DB and provider", emailId);
        } catch (Exception e) {
            log.warn("Failed to delete email {} from provider: {}", emailId, e.getMessage());
            // Optionally we could still delete locally if we want the app to be "clean" even if provider fails
            emailRepository.delete(email);
            notifyDeleted(account.getId(), emailId);
        }
    }
    public List<ContactDto> getContacts() {
        return emailSenderRepository.findAllByOrderByBestKnownNameAsc().stream()
                .map(sender -> ContactDto.builder()
                        .name(sender.getBestKnownName())
                        .email(sender.getEmail())
                        .build())
                .collect(Collectors.toList());
    }
}
