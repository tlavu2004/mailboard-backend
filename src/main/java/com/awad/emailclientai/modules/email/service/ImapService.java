package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.AttachmentResourceDto;
import org.springframework.scheduling.annotation.Async;
import com.awad.emailclientai.modules.email.dto.response.MailFolderDto;
import com.awad.emailclientai.modules.email.dto.response.MailMessageDetailDto;
import com.awad.emailclientai.modules.email.dto.response.MailMessageDto;
import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.shared.service.EncryptionService;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Service for connecting to email servers via IMAP protocol.
 * Supports both Basic Authentication (password/app-password) and OAuth2 (XOAUTH2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapService {

    private final EncryptionService encryptionService;
    private final GoogleTokenService googleTokenService;

    /**
     * Tests the connection to an IMAP server.
     *
     * @param account The email account to test
     * @return true if connection successful, false otherwise
     */
    public boolean testConnection(EmailAccount account) {
        try (Store store = connectToStore(account)) {
            return store.isConnected();
        } catch (Exception e) {
            log.error("IMAP connection test failed for {}: {}", account.getEmailAddress(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retrieves the list of folders/mailboxes from the email account.
     */
    public List<MailFolderDto> getFolders(EmailAccount account) throws MessagingException {
        List<MailFolderDto> folders = new ArrayList<>();
        
        try (Store store = connectToStore(account)) {
            Folder defaultFolder = store.getDefaultFolder();
            Folder[] allFolders = defaultFolder.list("*");
            
            for (Folder folder : allFolders) {
                // Skip folders that can't hold messages
                if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    continue;
                }
                
                folder.open(Folder.READ_ONLY);
                
                MailFolderDto dto = MailFolderDto.builder()
                        .name(folder.getFullName())
                        .displayName(getDisplayName(folder.getFullName()))
                        .messageCount(folder.getMessageCount())
                        .unreadCount(folder.getUnreadMessageCount())
                        .type(determineFolderType(folder.getFullName()))
                        .build();
                
                folders.add(dto);
                folder.close(false);
            }
        }
        
        // Sort folders: INBOX first, then common folders, then custom
        folders.sort(Comparator.comparingInt(this::getFolderPriority));
        
        return folders;
    }

    /**
     * Retrieves messages from a specific folder with pagination.
     *
     * @param account    The email account
     * @param folderName The folder name (e.g., "INBOX")
     * @param page       Page number (0-indexed)
     * @param size       Number of messages per page
     * @return List of message DTOs (headers only)
     */
    public List<MailMessageDto> getMessages(EmailAccount account, String folderName, 
                                             int page, int size) throws MessagingException {
        List<MailMessageDto> messages = new ArrayList<>();
        
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            
            int totalMessages = folder.getMessageCount();
            log.info("Opening IMAP folder: {} (Total messages: {})", folderName, totalMessages);
            if (totalMessages == 0) {
                folder.close(false);
                return messages;
            }
            
            // Calculate message range (IMAP messages are 1-indexed, newest first)
            int end = totalMessages - (page * size);
            int start = Math.max(1, end - size + 1);
            log.info("Fetching IMAP message range: {} to {} (page: {}, size: {})", start, end, page, size);
            
            if (end < 1) {
                folder.close(false);
                return messages;
            }
            
            Message[] imapMessages = folder.getMessages(start, end);
            
            // Fetch headers efficiently
            FetchProfile fetchProfile = new FetchProfile();
            fetchProfile.add(FetchProfile.Item.ENVELOPE);
            fetchProfile.add(FetchProfile.Item.FLAGS);
            fetchProfile.add(FetchProfile.Item.CONTENT_INFO);
            fetchProfile.add(UIDFolder.FetchProfileItem.UID);
            
            // Add Gmail labels to FetchProfile using the gimap provider's FetchProfileItem
            try {
                fetchProfile.add(org.eclipse.angus.mail.gimap.GmailFolder.FetchProfileItem.LABELS);
                fetchProfile.add(org.eclipse.angus.mail.gimap.GmailFolder.FetchProfileItem.MSGID);
                fetchProfile.add(org.eclipse.angus.mail.gimap.GmailFolder.FetchProfileItem.THRID);
                log.info("Added Gmail LABELS, MSGID, and THRID FetchProfileItem to profile.");
            } catch (Exception e) {
                log.warn("Failed to add Gmail labels FetchProfile item: {}", e.getMessage());
                fetchProfile.add("X-GM-LABELS");
            }

            folder.fetch(imapMessages, fetchProfile);
            
            // Convert to DTOs (reverse order for newest first)
            for (int i = imapMessages.length - 1; i >= 0; i--) {
                Message msg = imapMessages[i];
                messages.add(convertToDto(msg, folder));
            }
            
            folder.close(false);
        }
        
        return messages;
    }

    /**
     * Retrieves the full details of a specific message.
     *
     * @param account    The email account
     * @param folderName The folder name
     * @param uid        The IMAP UID of the message
     * @return Full message details including body and attachments
     */
    public MailMessageDetailDto getMessageDetail(EmailAccount account, String folderName, 
                                                  long uid) throws MessagingException, IOException {
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            
            if (!(folder instanceof UIDFolder)) {
                throw new MessagingException("Folder does not support UIDs");
            }
            
            UIDFolder uidFolder = (UIDFolder) folder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message == null) {
                folder.close(false);
                throw new MessagingException("Message not found with UID: " + uid);
            }
            
            MailMessageDetailDto detail = convertToDetailDto(message, uid);
            folder.close(false);
            
            return detail;
        }
    }

    /**
     * Marks a message as read/unread.
     */
    public void setMessageRead(EmailAccount account, String folderName, 
                                long uid, boolean read) throws MessagingException {
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            
            UIDFolder uidFolder = (UIDFolder) folder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message != null) {
                message.setFlag(Flags.Flag.SEEN, read);
            }
            
            folder.close(false);
        }
    }

    /**
     * Synchronizes a label change on a message using Gmail's native label API.
     * Uses GmailFolder.setLabels() to directly add/remove labels on the message
     * from the source folder (INBOX), avoiding UID mismatch issues across folders.
     *
     * @param oldLabelName the old label to remove (can be null to skip removal)
     * @param newLabelName the new label to add
     */
    public void syncLabel(EmailAccount account, String folderName, long uid, 
                          String oldLabelName, String newLabelName) throws MessagingException {
        if (newLabelName == null || newLabelName.isBlank()) return;
        
        try (Store store = connectToStore(account)) {
            Folder sourceFolder = store.getFolder(folderName);
            if (!sourceFolder.exists()) {
                log.error("Source folder {} does not exist", folderName);
                return;
            }
            
            sourceFolder.open(Folder.READ_WRITE);
            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message == null) {
                log.warn("Message with UID {} not found in folder {}", uid, folderName);
                sourceFolder.close(false);
                return;
            }

            Message[] msgs = new Message[]{message};

            // Use GmailFolder API to directly add/remove labels
            if (sourceFolder instanceof org.eclipse.angus.mail.gimap.GmailFolder gmailFolder) {
                // 1. Remove old label first
                if (oldLabelName != null && !oldLabelName.isBlank() && !oldLabelName.equals(newLabelName)) {
                    gmailFolder.setLabels(msgs, new String[]{oldLabelName}, false);
                    log.info("Removed label '{}' for email UID {}", oldLabelName, uid);
                }
                
                // 2. Add new label
                if (!newLabelName.equals(oldLabelName)) {
                    gmailFolder.setLabels(msgs, new String[]{newLabelName}, true);
                    log.info("Added label '{}' for email UID {}", newLabelName, uid);
                }
            } else {
                // Fallback for non-Gmail: use copy approach
                log.info("Non-Gmail folder, using copy approach for label sync");
                if (!newLabelName.equals(oldLabelName)) {
                    Folder targetFolder = store.getFolder(newLabelName);
                    if (!targetFolder.exists()) {
                        targetFolder.create(Folder.HOLDS_MESSAGES);
                    }
                    sourceFolder.copyMessages(msgs, targetFolder);
                    log.info("Copied message to folder '{}' for UID {}", newLabelName, uid);
                }
            }

            sourceFolder.close(false);
        }
    }

    /**
     * Marks a message as starred/flagged.
     */
    public void setMessageStarred(EmailAccount account, String folderName, 
                                   long uid, boolean starred) throws MessagingException {
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            
            UIDFolder uidFolder = (UIDFolder) folder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message != null) {
                message.setFlag(Flags.Flag.FLAGGED, starred);
            }
            
            folder.close(false);
        }
    }

    /**
     * Deletes a message (marks as deleted).
     */
    public void deleteMessage(EmailAccount account, String folderName, 
                               long uid) throws MessagingException {
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            
            UIDFolder uidFolder = (UIDFolder) folder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message != null) {
                message.setFlag(Flags.Flag.DELETED, true);
            }
            
            folder.close(true); // expunge on close
        }
    }

    /**
     * Moves a message to the Trash folder.
     */
    public void trashMessage(EmailAccount account, String folderName, long uid) throws MessagingException {
        try (Store store = connectToStore(account)) {
            Folder sourceFolder = store.getFolder(folderName);
            sourceFolder.open(Folder.READ_WRITE);
            
            UIDFolder uidFolder = (UIDFolder) sourceFolder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message == null) {
                sourceFolder.close(false);
                return;
            }

            // Find trash folder by common names
            String[] trashNames = {"[Gmail]/Trash", "[Gmail]/Thùng rác", "Trash", "Deleted Items", "Deleted"};
            Folder trashFolder = null;
            for (String name : trashNames) {
                try {
                    Folder f = store.getFolder(name);
                    if (f.exists()) {
                        trashFolder = f;
                        break;
                    }
                } catch (Exception e) {
                    // skip
                }
            }

            if (trashFolder != null) {
                sourceFolder.copyMessages(new Message[]{message}, trashFolder);
                message.setFlag(Flags.Flag.DELETED, true);
                log.info("Moved message UID {} to trash folder: {}", uid, trashFolder.getFullName());
            } else {
                // Fallback to just marking deleted
                message.setFlag(Flags.Flag.DELETED, true);
                log.warn("Could not find Trash folder for {}, falling back to DELETED flag", account.getEmailAddress());
            }

            sourceFolder.close(true); // expunge from source
        }
    }

    /**
     * Downloads an attachment from a message.
     */
    public AttachmentResourceDto downloadAttachment(EmailAccount account, String folderName, 
                                           long uid, String attachmentId) 
            throws MessagingException, IOException {
        try (Store store = connectToStore(account)) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            
            UIDFolder uidFolder = (UIDFolder) folder;
            Message message = uidFolder.getMessageByUID(uid);
            
            if (message == null) {
                folder.close(false);
                throw new MessagingException("Message not found");
            }
            
            int targetIndex = Integer.parseInt(attachmentId);
            int[] currentIndex = {0};
            BodyPart foundPart = null;

            Object content = message.getContent();
            if (content instanceof Multipart) {
                foundPart = findAttachmentPartRecursive((Multipart) content, targetIndex, currentIndex);
            }
            
            if (foundPart == null) {
                folder.close(false);
                throw new MessagingException("Attachment not found at index: " + targetIndex);
            }

            log.info("[DeepDownload] Found target attachment part: {}, Type: {}", 
                    foundPart.getFileName(), foundPart.getContentType());

            // Read stream to memory to allow closing folder/store
            byte[] contentBytes = foundPart.getInputStream().readAllBytes();
            
            AttachmentResourceDto result = AttachmentResourceDto.builder()
                    .inputStream(new java.io.ByteArrayInputStream(contentBytes))
                    .filename(foundPart.getFileName() != null ? foundPart.getFileName() : "attachment-" + targetIndex)
                    .contentType(foundPart.getContentType())
                    .size(contentBytes.length)
                    .build();

            folder.close(false);
            return result;
        }
    }

    private BodyPart findAttachmentPartRecursive(Multipart multipart, int targetIndex, int[] currentIndex) 
            throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            
            // OPTIMIZATION: Check if it's an attachment BEFORE loading content (V10.28)
            if (isActualAttachment(bodyPart)) {
                if (currentIndex[0] == targetIndex) {
                    return bodyPart;
                }
                currentIndex[0]++;
            } 
            // LIGHTWEIGHT CHECK: Only recurse if it's a multipart without downloading everything first
            else if (bodyPart.isMimeType("multipart/*")) {
                Object content = bodyPart.getContent();
                if (content instanceof Multipart) {
                    BodyPart nested = findAttachmentPartRecursive((Multipart) content, targetIndex, currentIndex);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    private boolean isActualAttachment(BodyPart bodyPart) throws MessagingException {
        String disposition = bodyPart.getDisposition();
        String fileName = bodyPart.getFileName();
        String contentId = getContentId(bodyPart);

        // 1. Explicitly marked as attachment -> Always include
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return true;
        }

        // 2. Has a filename -> Almost certainly a user file, even if it has a CID (V10.29)
        if (fileName != null && !fileName.trim().isEmpty()) {
            // Check if it's just a tiny tracking pixel or tiny icon (optional safety)
            // But usually, if it has a filename, the user expects to see it.
            return true;
        }

        // 3. Has a Content-ID but NO filename -> This is a decorative inline element (e.g., logo)
        // We skip these for the "Downloads" list to keep it clean.
        if (contentId != null && fileName == null) {
            return false;
        }

        return false;
    }

    /**
     * Appends a sent message to the Sent folder using IMAP APPEND.
     * This is called after sending via SMTP to save a copy in the Sent folder.
     *
     * @param account The email account
     * @param message The MimeMessage that was sent
     */
    @Async
    public void appendToSentFolder(EmailAccount account, jakarta.mail.internet.MimeMessage message) 
            throws MessagingException {
        try (Store store = connectToStore(account)) {
            String sentFolderName = getSentFolderName(account.getProvider());
            Folder sentFolder = store.getFolder(sentFolderName);
            
            // If primary sent folder doesn't exist, try alternatives
            if (!sentFolder.exists()) {
                log.warn("Sent folder '{}' not found, trying alternatives", sentFolderName);
                sentFolder = findSentFolder(store);
            }
            
            if (sentFolder != null && sentFolder.exists()) {
                sentFolder.open(Folder.READ_WRITE);
                // Mark as read (SEEN) since we sent it
                message.setFlag(Flags.Flag.SEEN, true);
                sentFolder.appendMessages(new Message[]{message});
                sentFolder.close(false);
                log.info("Message appended to Sent folder: {}", sentFolder.getFullName());
            } else {
                log.warn("Could not find Sent folder for account: {}", account.getEmailAddress());
            }
        }
    }

    /**
     * Returns the Sent folder name based on the email provider.
     */
    private String getSentFolderName(EmailProvider provider) {
        if (provider == null) {
            return "Sent";
        }
        return switch (provider) {
            case GMAIL -> "[Gmail]/Sent Mail";
            case OUTLOOK -> "Sent";
            case YAHOO -> "Sent";
            default -> "Sent";
        };
    }

    /**
     * Attempts to find the Sent folder by common names.
     */
    private Folder findSentFolder(Store store) throws MessagingException {
        String[] possibleNames = {
            "Sent", "Sent Items", "Sent Mail", 
            "[Gmail]/Sent Mail", "INBOX.Sent", "INBOX.Sent Items"
        };
        
        for (String name : possibleNames) {
            try {
                Folder folder = store.getFolder(name);
                if (folder.exists()) {
                    return folder;
                }
            } catch (MessagingException e) {
                // Ignore and try next
            }
        }
        return null;
    }

    // ================== Private Helper Methods ==================

    private Store connectToStore(EmailAccount account) throws MessagingException {
        return connectToStoreInternal(account, true);
    }

    private Store connectToStoreInternal(EmailAccount account, boolean retryOnAuthFailure) throws MessagingException {
        Properties props = new Properties();
        
        // Use 'gimaps' for Gmail (enables X-GM-LABELS, X-GM-MSGID, etc.), 'imaps' for others
        boolean isGmail = account.getImapHost() != null && 
                account.getImapHost().toLowerCase().contains("gmail");
        String protocol = isGmail ? "gimaps" : "imaps";
        
        log.debug("Connecting to {} using protocol: {}", account.getImapHost(), protocol);
        
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", account.getImapHost());
        props.put("mail." + protocol + ".port", String.valueOf(account.getImapPort()));
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(account.getImapSsl()));
        props.put("mail." + protocol + ".ssl.trust", "*");
        
        // Robust SSL/TLS settings
        props.put("mail." + protocol + ".ssl.protocols", "TLSv1.2 TLSv1.3");
        
        // Timeouts (Increased for stability in container environments)
        props.put("mail." + protocol + ".connectiontimeout", "30000"); // 30s
        props.put("mail." + protocol + ".timeout", "60000");           // 60s
        props.put("mail." + protocol + ".writetimeout", "30000");     // 30s
        
        // Performance and Stability
        props.put("mail." + protocol + ".partialfetch", "false");
        props.put("mail." + protocol + ".fetchsize", "81920"); // 80KB buffer
        props.put("mail." + protocol + ".statuscachetimeout", "10000");

        if (account.getAuthType() == EmailAuthType.OAUTH2) {
            // For OAuth2, password is the access token
            // Use XOAUTH2 authentication mechanism
            props.put("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
            props.put("mail." + protocol + ".sasl.enable", "true");
            props.put("mail." + protocol + ".sasl.mechanisms", "XOAUTH2");
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);

        String password = encryptionService.decrypt(account.getEncryptedPassword());

        try {
            store.connect(account.getImapHost(), account.getUsername(), password);
            return store;
        } catch (AuthenticationFailedException e) {
            if (retryOnAuthFailure && account.getAuthType() == EmailAuthType.OAUTH2) {
                log.info("IMAP authentication failed for {}, attempting token refresh...", account.getEmailAddress());
                String newAccessToken = googleTokenService.refreshAccessToken(account);
                if (newAccessToken != null) {
                    return connectToStoreInternal(account, false);
                }
            }
            throw e;
        }
    }

    private MailMessageDto convertToDto(Message message, Folder folder) throws MessagingException {
        UIDFolder uidFolder = (UIDFolder) folder;
        long uid = uidFolder.getUID(message);
        
        Address[] fromAddresses = message.getFrom();
        String from = "";
        String fromName = "";
        if (fromAddresses != null && fromAddresses.length > 0) {
            InternetAddress ia = (InternetAddress) fromAddresses[0];
            from = ia.getAddress();
            fromName = ia.getPersonal() != null ? ia.getPersonal() : from;
        }

        List<String> to = extractAddresses(message.getRecipients(Message.RecipientType.TO));
        List<String> cc = extractAddresses(message.getRecipients(Message.RecipientType.CC));

        Flags flags = message.getFlags();
        boolean read = flags.contains(Flags.Flag.SEEN);
        boolean starred = flags.contains(Flags.Flag.FLAGGED);

        // Check for attachments (precise check)
        boolean hasAttachments = false;
        try {
            hasAttachments = hasActualAttachments(message);
        } catch (IOException e) {
            log.warn("Failed to check attachments for message {}: {}", uid, e.getMessage());
        }

        LocalDateTime sentAt = message.getSentDate() != null 
                ? message.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : null;
        LocalDateTime receivedAt = message.getReceivedDate() != null 
                ? message.getReceivedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : sentAt;

        // Fetch Gmail labels using raw IMAP FETCH X-GM-LABELS command
        List<String> labels = fetchGmailLabels(message, folder);

        String body = fetchBodyContent(message);
        String preview = generatePreview(message);

        // Collect attachment metadata
        List<MailMessageDto.AttachmentMetadataDto> attachments = new ArrayList<>();
        try {
            if (message.getContent() instanceof Multipart) {
                collectAttachmentMetadata((Multipart) message.getContent(), attachments, new int[]{0});
            }
        } catch (Exception e) {
            log.warn("Failed to extract attachment metadata for UID {}: {}", uid, e.getMessage());
        }

        return MailMessageDto.builder()
                .uid(uid)
                .messageId(getHeaderValue(message, "Message-ID"))
                .from(from)
                .fromName(fromName)
                .to(to)
                .cc(cc)
                .subject(message.getSubject())
                .preview(preview) 
                .body(body) // Fetch limited body
                .sentAt(sentAt)
                .receivedAt(receivedAt)
                .read(read)
                .starred(starred)
                .hasAttachments(hasAttachments || !attachments.isEmpty())
                .attachments(attachments)
                .labels(labels)
                .gmailMessageId(extractGmailMsgId(message))
                .threadId(extractGmailThreadId(message))
                .size(message.getSize())
                .build();
    }

    /**
     * Fetches Gmail labels for a message using the GIMAP provider's GmailMessage.getLabels().
     * Requires 'gimaps' protocol (provided by org.eclipse.angus:gimap dependency).
     */
    private List<String> fetchGmailLabels(Message message, Folder folder) {
        List<String> labels = new ArrayList<>();
        try {
            // With gimaps protocol, messages should be GmailMessage instances
            if (message instanceof org.eclipse.angus.mail.gimap.GmailMessage gmailMsg) {
                String[] gmLabels = gmailMsg.getLabels();
                if (gmLabels != null && gmLabels.length > 0) {
                    for (String label : gmLabels) {
                        // Filter out Gmail system labels (start with \)
                        if (label != null && !label.startsWith("\\")) {
                            labels.add(label);
                        }
                    }
                    log.info("[GmailLabels] Got {} labels for msg #{}: {}", 
                            labels.size(), message.getMessageNumber(), labels);
                } else {
                    log.debug("[GmailLabels] No labels for msg #{}", message.getMessageNumber());
                }
            } else {
                log.debug("[GmailLabels] Message is not a GmailMessage (class={}), skipping label fetch",
                        message.getClass().getName());
            }
        } catch (MessagingException e) {
            log.warn("[GmailLabels] MessagingException: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[GmailLabels] Exception: {}", e.getMessage(), e);
        }
        return labels;
    }


    private String fetchBodyContent(Message message) {
         try {
             Object content = message.getContent();
             log.debug("Fetching body content, content type: {}", content != null ? content.getClass().getName() : "null");
             if (content instanceof String) {
                 return (String) content;
             } else if (content instanceof Multipart) {
                 return getTextFromMultipart((Multipart) content);
             }
         } catch (Exception e) {
             log.warn("Failed to fetch body content for message: {}", e.getMessage());
             return "";
         }
         return "";
    }

    private String generatePreview(Message message) {
        try {
            String body = fetchBodyContent(message);
            String plainText = stripHtml(body);
            if (plainText.length() > 150) {
                return plainText.substring(0, 147) + "...";
            }
            return plainText;
        } catch (Exception e) {
            return "";
        }
    }

    public String stripHtml(String html) {
        if (html == null) return "";
        // 1. Remove style tags and their content
        String scriptRegex = "<script[^>]*>[\\s\\S]*?</script>";
        String styleRegex = "<style[^>]*>[\\s\\S]*?</style>";
        String result = html.replaceAll(scriptRegex, "").replaceAll(styleRegex, "");
        // 2. Remove all other HTML tags
        result = result.replaceAll("<[^>]*>", "");
        // 3. Unescape entities
        return result.replaceAll("&nbsp;", " ").replaceAll("&lt;", "<").replaceAll("&gt;", ">").trim();
    }

    private String getTextFromMultipart(Multipart multipart) throws Exception {
        String plainText = null;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/html")) {
                return (String) bodyPart.getContent();
            } else if (bodyPart.isMimeType("text/plain")) {
                if (plainText == null) plainText = (String) bodyPart.getContent();
            } else if (bodyPart.getContent() instanceof Multipart) {
                String result = getTextFromMultipart((Multipart) bodyPart.getContent());
                // If the recursive call found HTML, return it immediately
                // We can check if it looks like HTML by seeing if it contains '<'
                if (result != null && (result.contains("<html") || result.contains("<body") || result.contains("<div"))) {
                    return result;
                }
                if (plainText == null) plainText = result;
            }
        }
        return plainText;
    }

    private MailMessageDetailDto convertToDetailDto(Message message, long uid) 
            throws MessagingException, IOException {
        
        Address[] fromAddresses = message.getFrom();
        String from = "";
        String fromName = "";
        if (fromAddresses != null && fromAddresses.length > 0) {
            InternetAddress ia = (InternetAddress) fromAddresses[0];
            from = ia.getAddress();
            fromName = ia.getPersonal() != null ? ia.getPersonal() : from;
        }

        Flags flags = message.getFlags();
        
        // Extract body
        String bodyText = "";
        String bodyHtml = "";
        List<MailMessageDetailDto.AttachmentDto> attachments = new ArrayList<>();
        
        processContent(message, bodyText, bodyHtml, attachments, 0);

        // For simplicity, using mutable holders
        StringBuilder textBuilder = new StringBuilder();
        StringBuilder htmlBuilder = new StringBuilder();
        processContentRecursive(message.getContent(), message.getContentType(), 
                textBuilder, htmlBuilder, attachments, new int[]{0}, null);

        LocalDateTime sentAt = message.getSentDate() != null 
                ? message.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : null;
        LocalDateTime receivedAt = message.getReceivedDate() != null 
                ? message.getReceivedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : sentAt;

        return MailMessageDetailDto.builder()
                .uid(uid)
                .messageId(getHeaderValue(message, "Message-ID"))
                .from(from)
                .fromName(fromName)
                .to(extractAddresses(message.getRecipients(Message.RecipientType.TO)))
                .cc(extractAddresses(message.getRecipients(Message.RecipientType.CC)))
                .bcc(extractAddresses(message.getRecipients(Message.RecipientType.BCC)))
                .replyTo(getHeaderValue(message, "Reply-To"))
                .subject(message.getSubject())
                .sentAt(sentAt)
                .receivedAt(receivedAt)
                .read(flags.contains(Flags.Flag.SEEN))
                .starred(flags.contains(Flags.Flag.FLAGGED))
                .bodyText(textBuilder.toString())
                .bodyHtml(htmlBuilder.toString())
                .attachments(attachments)
                .gmailMessageId(extractGmailMsgId(message))
                .threadId(extractGmailThreadId(message))
                .inReplyTo(getHeaderValue(message, "In-Reply-To"))
                .references(parseReferences(getHeaderValue(message, "References")))
                .build();
    }

    private void processContentRecursive(Object content, String contentType,
                                          StringBuilder textBuilder, StringBuilder htmlBuilder,
                                          List<MailMessageDetailDto.AttachmentDto> attachments,
                                          int[] attachmentIndex, BodyPart bodyPart) throws MessagingException, IOException {
        
        String disposition = bodyPart != null ? bodyPart.getDisposition() : null;
        String contentId = bodyPart != null ? getContentId(bodyPart) : null;
        String fileName = bodyPart != null ? bodyPart.getFileName() : null;

        log.info("[IMAP-XRAY-V10] Inspecting Part: Type={}, Disp={}, ID={}, File={}", 
                contentType != null ? contentType.split(";")[0] : "null", disposition, contentId, fileName);
        
        // 1. Identify if this part should be treated as an attachment/inline resource (V10.26)
        boolean isAttachment = bodyPart != null && isActualAttachment(bodyPart);

        if (isAttachment) {
            log.info("[IMAP-XRAY-HIT-V10] Capturing as real attachment: {}, CID: {}, Type: {}", 
                    fileName != null ? fileName : "attachment", contentId, contentType);
            attachments.add(MailMessageDetailDto.AttachmentDto.builder()
                    .id(String.valueOf(attachmentIndex[0]++))
                    .filename(fileName != null ? fileName : "attachment-" + attachmentIndex[0])
                    .contentType(contentType)
                    .size(bodyPart != null ? bodyPart.getSize() : 0)
                    .inline(false)
                    .contentId(contentId)
                    .build());
            return; 
        }

        // 2. Process content... (logic continues for text/html and inline CIDs)

        // 2. Process content based on type
        if (content instanceof String) {
            String body = (String) content;
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                htmlBuilder.append(body);
            } else {
                textBuilder.append(body);
            }
        } else if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart childPart = multipart.getBodyPart(i);
                processContentRecursive(childPart.getContent(), childPart.getContentType(),
                        textBuilder, htmlBuilder, attachments, attachmentIndex, childPart);
            }
        } else if (content instanceof MimeBodyPart) {
            MimeBodyPart mbp = (MimeBodyPart) content;
            processContentRecursive(mbp.getContent(), mbp.getContentType(),
                    textBuilder, htmlBuilder, attachments, attachmentIndex, mbp);
        }
    }

    private void processContent(Message message, String bodyText, String bodyHtml,
                                 List<MailMessageDetailDto.AttachmentDto> attachments, 
                                 int attachmentIndex) {
        // Placeholder - actual implementation in processContentRecursive
    }

    private String getContentId(BodyPart bodyPart) throws MessagingException {
        // Try Content-ID and Content-Id
        String[] headers = bodyPart.getHeader("Content-ID");
        if (headers == null || headers.length == 0) {
            headers = bodyPart.getHeader("Content-Id");
        }
        
        if (headers != null && headers.length > 0) {
            String cid = headers[0].trim();
            // Remove angle brackets if present
            return cid.replaceAll("[<>]", "").trim();
        }
        return null;
    }

    private List<String> extractAddresses(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (Address addr : addresses) {
                if (addr instanceof InternetAddress) {
                    result.add(((InternetAddress) addr).getAddress());
                } else {
                    result.add(addr.toString());
                }
            }
        }
        return result;
    }

    private String getHeaderValue(Message message, String headerName) throws MessagingException {
        String[] headers = message.getHeader(headerName);
        return (headers != null && headers.length > 0) ? headers[0] : null;
    }

    private List<String> parseReferences(String references) {
        if (references == null || references.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.asList(references.split("\\s+"));
    }

    private String getDisplayName(String folderName) {
        // Remove provider prefixes like [Gmail]/, [Outlook]/
        String display = folderName.replaceAll("^\\[.*?\\]/", "");
        // Capitalize first letter
        if (!display.isEmpty()) {
            return display.substring(0, 1).toUpperCase() + display.substring(1);
        }
        return display;
    }

    private String determineFolderType(String folderName) {
        String lower = folderName.toLowerCase();
        if (lower.equals("inbox")) return "INBOX";
        if (lower.contains("sent")) return "SENT";
        if (lower.contains("draft")) return "DRAFTS";
        if (lower.contains("trash") || lower.contains("deleted")) return "TRASH";
        if (lower.contains("spam") || lower.contains("junk")) return "SPAM";
        if (lower.contains("archive")) return "ARCHIVE";
        return "CUSTOM";
    }

    private int getFolderPriority(MailFolderDto folder) {
        switch (folder.getType()) {
            case "INBOX": return 0;
            case "SENT": return 1;
            case "DRAFTS": return 2;
            case "SPAM": return 3;
            case "TRASH": return 4;
            case "ARCHIVE": return 5;
            default: return 10;
        }
    }

    private boolean hasActualAttachments(Message message) throws MessagingException, IOException {
        Object content = message.getContent();
        if (content instanceof Multipart) {
            return checkMultipartAttachments((Multipart) content);
        }
        return false;
    }

    private boolean checkMultipartAttachments(Multipart multipart) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            
            if (isActualAttachment(bodyPart)) {
                return true;
            }
            
            // Recursive check for nested multiparts (Optimized V10.28)
            if (bodyPart.isMimeType("multipart/*")) {
                Object content = bodyPart.getContent();
                if (content instanceof Multipart) {
                    if (checkMultipartAttachments((Multipart) content)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void collectAttachmentMetadata(Multipart multipart, 
                                           List<MailMessageDto.AttachmentMetadataDto> attachments,
                                           int[] index) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            
            if (isActualAttachment(bodyPart)) {
                attachments.add(MailMessageDto.AttachmentMetadataDto.builder()
                        .id(String.valueOf(index[0]++))
                        .filename(bodyPart.getFileName() != null ? bodyPart.getFileName() : "attachment-" + index[0])
                        .contentType(bodyPart.getContentType())
                        .size(bodyPart.getSize())
                        .contentId(getContentId(bodyPart))
                        .inline(false)
                        .build());
            } else if (bodyPart.isMimeType("multipart/*")) {
                Object content = bodyPart.getContent();
                if (content instanceof Multipart) {
                    collectAttachmentMetadata((Multipart) content, attachments, index);
                }
            }
        }
    }

    private String extractGmailMsgId(Message message) {
        try {
            if (message instanceof org.eclipse.angus.mail.gimap.GmailMessage gmailMsg) {
                return Long.toHexString(gmailMsg.getMsgId());
            }
        } catch (MessagingException e) {
            log.warn("Failed to extract Gmail MsgId: {}", e.getMessage());
        }
        return null;
    }

    private String extractGmailThreadId(Message message) {
        try {
            if (message instanceof org.eclipse.angus.mail.gimap.GmailMessage gmailMsg) {
                // Some versions call it getThrId or similar, but MSGID is more critical
                return Long.toHexString(gmailMsg.getMsgId()); // Fallback to msgId as threadId usually contains it
            }
        } catch (MessagingException e) {
            log.warn("Failed to extract Gmail ThreadId: {}", e.getMessage());
        }
        return null;
    }
}
