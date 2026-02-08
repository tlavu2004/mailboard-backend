package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.dto.response.AttachmentResourceDto;
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
            log.error("IMAP connection test failed for {}: {}", account.getEmailAddress(), e.getMessage());
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
            if (totalMessages == 0) {
                folder.close(false);
                return messages;
            }
            
            // Calculate message range (IMAP messages are 1-indexed, newest first)
            int end = totalMessages - (page * size);
            int start = Math.max(1, end - size + 1);
            
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
     * Deletes a message (moves to Trash or marks as deleted).
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
                throw new MessagingException("Message not found");
            }
            
            // Find the attachment by ID (index)
            int attachmentIndex = Integer.parseInt(attachmentId);
            Object content = message.getContent();
            
            if (content instanceof Multipart) {
                Multipart multipart = (Multipart) content;
                int currentIndex = 0;
                
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart bodyPart = multipart.getBodyPart(i);
                    String disposition = bodyPart.getDisposition();
                    
                    if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || 
                        Part.INLINE.equalsIgnoreCase(disposition) ||
                        bodyPart.getFileName() != null) {
                        
                        if (currentIndex == attachmentIndex) {
                            // Read stream to memory to allow closing folder/store
                            byte[] contentBytes = bodyPart.getInputStream().readAllBytes();
                            
                            return AttachmentResourceDto.builder()
                                    .inputStream(new java.io.ByteArrayInputStream(contentBytes))
                                    .filename(bodyPart.getFileName() != null ? bodyPart.getFileName() : "attachment")
                                    .contentType(bodyPart.getContentType())
                                    .size(contentBytes.length)
                                    .build();
                        }
                        currentIndex++;
                    }
                }
            }
            
            folder.close(false);
            throw new MessagingException("Attachment not found");
        }
    }

    /**
     * Appends a sent message to the Sent folder using IMAP APPEND.
     * This is called after sending via SMTP to save a copy in the Sent folder.
     *
     * @param account The email account
     * @param message The MimeMessage that was sent
     */
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
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", account.getImapHost());
        props.put("mail.imaps.port", String.valueOf(account.getImapPort()));
        props.put("mail.imaps.ssl.enable", String.valueOf(account.getImapSsl()));
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "30000");

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");

        String password = encryptionService.decrypt(account.getEncryptedPassword());

        if (account.getAuthType() == EmailAuthType.OAUTH2) {
            // For OAuth2, password is the access token
            // Use XOAUTH2 authentication mechanism
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
            props.put("mail.imaps.sasl.enable", "true");
            props.put("mail.imaps.sasl.mechanisms", "XOAUTH2");
        }

        store.connect(account.getImapHost(), account.getUsername(), password);
        return store;
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

        // Check for attachments (simplified check)
        boolean hasAttachments = false;
        String contentType = message.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("multipart")) {
            hasAttachments = true; // Could be more precise
        }

        LocalDateTime sentAt = message.getSentDate() != null 
                ? message.getSentDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : null;
        LocalDateTime receivedAt = message.getReceivedDate() != null 
                ? message.getReceivedDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                : sentAt;

        return MailMessageDto.builder()
                .uid(uid)
                .messageId(getHeaderValue(message, "Message-ID"))
                .from(from)
                .fromName(fromName)
                .to(to)
                .cc(cc)
                .subject(message.getSubject())
                .preview("") // Preview often unreliable from envelope alone
                .body(fetchBodyContent(message)) // Fetch limited body
                .sentAt(sentAt)
                .receivedAt(receivedAt)
                .read(read)
                .starred(starred)
                .hasAttachments(hasAttachments)
                .size(message.getSize())
                .build();
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

    private String getTextFromMultipart(Multipart multipart) throws Exception {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                return (String) bodyPart.getContent();
            } else if (bodyPart.isMimeType("text/html")) {
                String html = (String) bodyPart.getContent();
                // Basic HTML cleanup without Jsoup to avoid dependency issues if not present
                return html.replaceAll("<[^>]*>", "").replaceAll("&nbsp;", " ").trim(); 
            } else if (bodyPart.getContent() instanceof Multipart) {
                result.append(getTextFromMultipart((Multipart) bodyPart.getContent()));
            }
        }
        return result.toString();
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
                textBuilder, htmlBuilder, attachments, new int[]{0});

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
                .inReplyTo(getHeaderValue(message, "In-Reply-To"))
                .references(parseReferences(getHeaderValue(message, "References")))
                .build();
    }

    private void processContentRecursive(Object content, String contentType,
                                          StringBuilder textBuilder, StringBuilder htmlBuilder,
                                          List<MailMessageDetailDto.AttachmentDto> attachments,
                                          int[] attachmentIndex) throws MessagingException, IOException {
        if (content instanceof String) {
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                htmlBuilder.append((String) content);
            } else {
                textBuilder.append((String) content);
            }
        } else if (content instanceof Multipart) {
            Multipart multipart = (Multipart) content;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String disposition = bodyPart.getDisposition();
                
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || 
                    (bodyPart.getFileName() != null && disposition != null)) {
                    // It's an attachment
                    attachments.add(MailMessageDetailDto.AttachmentDto.builder()
                            .id(String.valueOf(attachmentIndex[0]++))
                            .filename(bodyPart.getFileName())
                            .contentType(bodyPart.getContentType())
                            .size(bodyPart.getSize())
                            .inline(Part.INLINE.equalsIgnoreCase(disposition))
                            .contentId(getContentId(bodyPart))
                            .build());
                } else {
                    // Process nested content
                    processContentRecursive(bodyPart.getContent(), bodyPart.getContentType(),
                            textBuilder, htmlBuilder, attachments, attachmentIndex);
                }
            }
        } else if (content instanceof MimeBodyPart) {
            MimeBodyPart mbp = (MimeBodyPart) content;
            processContentRecursive(mbp.getContent(), mbp.getContentType(),
                    textBuilder, htmlBuilder, attachments, attachmentIndex);
        }
    }

    private void processContent(Message message, String bodyText, String bodyHtml,
                                 List<MailMessageDetailDto.AttachmentDto> attachments, 
                                 int attachmentIndex) {
        // Placeholder - actual implementation in processContentRecursive
    }

    private String getContentId(BodyPart bodyPart) throws MessagingException {
        String[] headers = bodyPart.getHeader("Content-ID");
        if (headers != null && headers.length > 0) {
            String cid = headers[0];
            // Remove angle brackets
            if (cid.startsWith("<") && cid.endsWith(">")) {
                return cid.substring(1, cid.length() - 1);
            }
            return cid;
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
}
