package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.shared.service.EncryptionService;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.Draft;
import java.util.Base64;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.MessagingException;
import java.io.ByteArrayOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GmailLabelService {

    private final EncryptionService encryptionService;
    private final GoogleTokenService googleTokenService;

    /**
     * Fetches all Gmail labels (system + user-created) for the given account.
     */
    public List<Map<String, String>> getLabels(EmailAccount account) {
        return getLabelsInternal(account, true);
    }

    private List<Map<String, String>> getLabelsInternal(EmailAccount account, boolean retryOnAuthFailure) {
        if (account.getProvider() != EmailProvider.GMAIL || account.getAuthType() != EmailAuthType.OAUTH2) {
            log.warn("getLabels called for non-Gmail/non-OAuth account: {}", account.getEmailAddress());
            return getDefaultLabels();
        }

        try {
            Gmail gmail = getGmailService(account);
            ListLabelsResponse response = gmail.users().labels().list("me").execute();
            List<Label> labels = response.getLabels();

            if (labels == null || labels.isEmpty()) {
                return getDefaultLabels();
            }

            return labels.stream()
                    .map(label -> {
                        Map<String, String> m = new HashMap<>();
                        m.put("id", label.getId());
                        m.put("name", label.getName());
                        m.put("type", label.getType() != null ? label.getType().toLowerCase() : "user");
                        return m;
                    })
                    .sorted(Comparator.comparing((Map<String, String> m) -> m.get("type").equals("system") ? 0 : 1)
                            .thenComparing(m -> m.get("name")))
                    .collect(Collectors.toList());

        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                log.info("Gmail Labels auth failed for {}, attempting token refresh...", account.getEmailAddress());
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return getLabelsInternal(account, false);
                }
            }
            log.error("Failed to fetch Gmail labels for {}: {}", account.getEmailAddress(), e.getMessage());
            return getDefaultLabels();
        } catch (Exception e) {
            log.error("Failed to fetch Gmail labels for {}: {}", account.getEmailAddress(), e.getMessage());
            return getDefaultLabels();
        }
    }

    /**
     * Creates a new label in Gmail for the given account.
     */
    public Map<String, String> createLabel(EmailAccount account, String labelName) {
        return createLabelInternal(account, labelName, true);
    }

    /**
     * Finds a Gmail message by RFC822 Message-ID and modifies its labels.
     */
    public void modifyMessageLabelsByMessageId(EmailAccount account, String rawMessageId, List<String> addLabelIds, List<String> removeLabelIds) {
        modifyMessageLabelsByMessageIdInternal(account, rawMessageId, addLabelIds, removeLabelIds, true);
    }

    /**
     * Modifies labels using Gmail message id when available, with RFC822 Message-ID fallback.
     */
    public void modifyMessageLabels(EmailAccount account, String rawMessageId, String gmailMessageId, List<String> addLabelIds, List<String> removeLabelIds) {
        modifyMessageLabelsInternal(account, rawMessageId, gmailMessageId, addLabelIds, removeLabelIds, true);
    }

    /**
     * Restores a trashed Gmail message using Gmail native untrash API.
     */
    public void untrashMessage(EmailAccount account, String rawMessageId, String rawGmailMessageId) {
        untrashMessageInternal(account, rawMessageId, rawGmailMessageId, true);
    }

    private Map<String, String> createLabelInternal(EmailAccount account, String labelName, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);

            Label newLabel = new Label()
                    .setName(labelName)
                    .setLabelListVisibility("labelShow")
                    .setMessageListVisibility("show");

            Label created = gmail.users().labels().create("me", newLabel).execute();

            Map<String, String> result = new HashMap<>();
            result.put("id", created.getId());
            result.put("name", created.getName());
            result.put("type", "user");
            log.info("Created Gmail label '{}' for {}", labelName, account.getEmailAddress());
            return result;

        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                log.info("Gmail create label auth failed for {}, attempting token refresh...", account.getEmailAddress());
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return createLabelInternal(account, labelName, false);
                }
            }
            log.error("Failed to create Gmail label for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to create Gmail label: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create Gmail label for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to create Gmail label: " + e.getMessage());
        }
    }

    private void modifyMessageLabelsByMessageIdInternal(EmailAccount account, String rawMessageId, List<String> addLabelIds, List<String> removeLabelIds, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);
            String normalizedMessageId = normalizeMessageId(rawMessageId);
            String query = "rfc822msgid:" + quoteIfNeeded(normalizedMessageId);

            ListMessagesResponse response = gmail.users().messages().list("me")
                    .setQ(query)
                    .setIncludeSpamTrash(true)
                    .setMaxResults(10L)
                    .execute();
            if (response.getMessages() == null || response.getMessages().isEmpty()) {
                log.warn("No Gmail message found for query {} on {}", query, account.getEmailAddress());
                return;
            }

            String gmailMessageId = response.getMessages().get(0).getId();
            ModifyMessageRequest request = new ModifyMessageRequest();
            if (addLabelIds != null) request.setAddLabelIds(addLabelIds);
            if (removeLabelIds != null) request.setRemoveLabelIds(removeLabelIds);

            gmail.users().messages().modify("me", gmailMessageId, request).execute();
            log.info("Modified Gmail message labels for {} using query {} (messageId={})", account.getEmailAddress(), query, normalizedMessageId);
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                log.info("Gmail message modify auth failed for {}, attempting token refresh...", account.getEmailAddress());
                if (googleTokenService.refreshAccessToken(account) != null) {
                    modifyMessageLabelsByMessageIdInternal(account, rawMessageId, addLabelIds, removeLabelIds, false);
                    return;
                }
            }
            log.error("Failed to modify Gmail message labels for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to modify Gmail message labels: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to modify Gmail message labels for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to modify Gmail message labels: " + e.getMessage());
        }
    }

    private void modifyMessageLabelsInternal(EmailAccount account, String rawMessageId, String rawGmailMessageId, List<String> addLabelIds, List<String> removeLabelIds, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);
            String gmailMessageId = resolveGmailMessageId(gmail, rawMessageId, rawGmailMessageId);

            ModifyMessageRequest request = new ModifyMessageRequest();
            if (addLabelIds != null) request.setAddLabelIds(addLabelIds);
            if (removeLabelIds != null) request.setRemoveLabelIds(removeLabelIds);

            gmail.users().messages().modify("me", gmailMessageId, request).execute();
            log.info("Modified Gmail message labels for {} (gmailMessageId={}, rfc822MessageId={})",
                    account.getEmailAddress(), gmailMessageId, normalizeMessageId(rawMessageId));
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                log.info("Gmail message modify auth failed for {}, attempting token refresh...", account.getEmailAddress());
                if (googleTokenService.refreshAccessToken(account) != null) {
                    modifyMessageLabelsInternal(account, rawMessageId, rawGmailMessageId, addLabelIds, removeLabelIds, false);
                    return;
                }
            }
            log.error("Failed to modify Gmail message labels for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to modify Gmail message labels: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to modify Gmail message labels for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to modify Gmail message labels: " + e.getMessage());
        }
    }

    public Map<String, String> createDraft(EmailAccount account, MimeMessage mimeMessage) throws IOException, MessagingException {
        try {
            return createDraftInternal(account, mimeMessage, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error", e);
        }
    }

    public Map<String, String> updateDraft(EmailAccount account, String draftId, MimeMessage mimeMessage) throws IOException, MessagingException {
        try {
            return updateDraftInternal(account, draftId, mimeMessage, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error", e);
        }
    }

    public void deleteDraft(EmailAccount account, String draftId) throws IOException {
        try {
            deleteDraftInternal(account, draftId, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error", e);
        }
    }

    private Map<String, String> createDraftInternal(EmailAccount account, MimeMessage mimeMessage, boolean retryOnAuthFailure) throws IOException, MessagingException, GeneralSecurityException {
        try {
            Gmail gmail = getGmailService(account);
            Message message = createGmailMessage(mimeMessage);
            Draft draft = new Draft();
            draft.setMessage(message);
            
            Draft created = gmail.users().drafts().create("me", draft).execute();
            log.info("Created Gmail draft {} for {}", created.getId(), account.getEmailAddress());
            
            Map<String, String> result = new HashMap<>();
            result.put("draftId", created.getId());
            result.put("messageId", created.getMessage().getId());
            return result;
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return createDraftInternal(account, mimeMessage, false);
                }
            }
            throw e;
        }
    }

    private Map<String, String> updateDraftInternal(EmailAccount account, String draftId, MimeMessage mimeMessage, boolean retryOnAuthFailure) throws IOException, MessagingException, GeneralSecurityException {
        try {
            Gmail gmail = getGmailService(account);
            Message message = createGmailMessage(mimeMessage);
            Draft draft = new Draft();
            draft.setMessage(message);
            
            Draft updated = gmail.users().drafts().update("me", draftId, draft).execute();
            log.info("Updated Gmail draft {} for {}", draftId, account.getEmailAddress());
            
            Map<String, String> result = new HashMap<>();
            result.put("draftId", updated.getId());
            result.put("messageId", updated.getMessage().getId());
            return result;
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return updateDraftInternal(account, draftId, mimeMessage, false);
                }
            }
            throw e;
        }
    }

    private void deleteDraftInternal(EmailAccount account, String draftId, boolean retryOnAuthFailure) throws IOException, GeneralSecurityException {
        try {
            Gmail gmail = getGmailService(account);
            gmail.users().drafts().delete("me", draftId).execute();
            log.info("Deleted Gmail draft {} for {}", draftId, account.getEmailAddress());
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    deleteDraftInternal(account, draftId, false);
                    return;
                }
            }
            throw e;
        }
    }

    public void trashDraft(EmailAccount account, String gmailMessageId) throws IOException {
        try {
            trashDraftInternal(account, gmailMessageId, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error", e);
        }
    }

    private void trashDraftInternal(EmailAccount account, String gmailMessageId, boolean retryOnAuthFailure) throws IOException, GeneralSecurityException {
        try {
            Gmail gmail = getGmailService(account);
            gmail.users().messages().trash("me", gmailMessageId).execute();
            log.info("Trashed Gmail draft message {} for {}", gmailMessageId, account.getEmailAddress());
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    trashDraftInternal(account, gmailMessageId, false);
                    return;
                }
            }
            throw e;
        }
    }

    public void deleteMessage(EmailAccount account, String messageId, String gmailMessageId) throws IOException {
        try {
            deleteMessageInternal(account, gmailMessageId, true);
        } catch (GeneralSecurityException e) {
            throw new IOException("Security error", e);
        }
    }

    private void deleteMessageInternal(EmailAccount account, String gmailMessageId, boolean retryOnAuthFailure) throws IOException, GeneralSecurityException {
        try {
            Gmail gmail = getGmailService(account);
            gmail.users().messages().delete("me", gmailMessageId).execute();
            log.info("Permanently deleted Gmail message {} for {}", gmailMessageId, account.getEmailAddress());
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    deleteMessageInternal(account, gmailMessageId, false);
                    return;
                }
            }
            throw e;
        }
    }

    private Message createGmailMessage(MimeMessage mimeMessage) throws IOException, MessagingException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);
        byte[] bytes = buffer.toByteArray();
        String encodedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Message message = new Message();
        message.setRaw(encodedEmail);
        return message;
    }

    private void untrashMessageInternal(EmailAccount account, String rawMessageId, String rawGmailMessageId, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);
            String gmailMessageId = resolveGmailMessageId(gmail, rawMessageId, rawGmailMessageId);

            Message response = gmail.users().messages().untrash("me", gmailMessageId).execute();
            log.info("Untrashed Gmail message for {} (gmailMessageId={}, rfc822MessageId={}, responseId={})",
                    account.getEmailAddress(), gmailMessageId, normalizeMessageId(rawMessageId), response != null ? response.getId() : "null");
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                log.info("Gmail untrash auth failed for {}, attempting token refresh...", account.getEmailAddress());
                if (googleTokenService.refreshAccessToken(account) != null) {
                    untrashMessageInternal(account, rawMessageId, rawGmailMessageId, false);
                    return;
                }
            }
            log.error("Failed to untrash Gmail message for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to untrash Gmail message: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to untrash Gmail message for {}: {}", account.getEmailAddress(), e.getMessage());
            throw new RuntimeException("Failed to untrash Gmail message: " + e.getMessage());
        }
    }

    private String resolveGmailMessageId(Gmail gmail, String rawMessageId, String rawGmailMessageId) throws IOException {
        String gmailMessageId = normalizeMessageId(rawGmailMessageId);
        if (gmailMessageId != null && !gmailMessageId.isBlank()) {
            return gmailMessageId;
        }

        String normalizedMessageId = normalizeMessageId(rawMessageId);
        if (normalizedMessageId == null || normalizedMessageId.isBlank()) {
            throw new RuntimeException("Missing both messageId and gmailMessageId for Gmail operation");
        }

        String query = "rfc822msgid:" + quoteIfNeeded(normalizedMessageId);
        ListMessagesResponse response = gmail.users().messages().list("me")
                .setQ(query)
                .setIncludeSpamTrash(true)
                .setMaxResults(10L)
                .execute();
        if (response.getMessages() == null || response.getMessages().isEmpty()) {
            throw new RuntimeException("No Gmail message found for rfc822 message-id: " + normalizedMessageId);
        }

        return response.getMessages().get(0).getId();
    }

    private String normalizeMessageId(String rawMessageId) {
        if (rawMessageId == null) return "";
        String value = rawMessageId.trim();
        if (value.startsWith("<") && value.endsWith(">") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String quoteIfNeeded(String value) {
        if (value == null) return "";
        if (value.contains(" ") || value.contains("@") || value.contains("<") || value.contains(">") || value.contains("\"")) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return value;
    }

    /**
     * Fallback when Gmail API is unreachable
     */
    private List<Map<String, String>> getDefaultLabels() {
        List<Map<String, String>> labels = new ArrayList<>();
        labels.add(Map.of("id", "INBOX", "name", "Inbox", "type", "system"));
        labels.add(Map.of("id", "STARRED", "name", "Starred", "type", "system"));
        labels.add(Map.of("id", "SENT", "name", "Sent", "type", "system"));
        labels.add(Map.of("id", "DRAFTS", "name", "Drafts", "type", "system"));
        labels.add(Map.of("id", "TRASH", "name", "Trash", "type", "system"));
        labels.add(Map.of("id", "SPAM", "name", "Spam", "type", "system"));
        return labels;
    }

    public List<com.google.api.services.gmail.model.History> getHistory(EmailAccount account, Long startHistoryId) {
        return getHistoryInternal(account, startHistoryId, true);
    }

    private List<com.google.api.services.gmail.model.History> getHistoryInternal(EmailAccount account, Long startHistoryId, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);
            var response = gmail.users().history().list("me")
                .setStartHistoryId(java.math.BigInteger.valueOf(startHistoryId))
                .execute();
            
            return response.getHistory();
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return getHistoryInternal(account, startHistoryId, false);
                }
            }
            log.error("Failed to fetch Gmail history for {}: {}", account.getEmailAddress(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch Gmail history for {}: {}", account.getEmailAddress(), e.getMessage());
            return null;
        }
    }

    public com.google.api.services.gmail.model.Message getMessage(EmailAccount account, String gmailMessageId) {
        return getMessageInternal(account, gmailMessageId, true);
    }

    private com.google.api.services.gmail.model.Message getMessageInternal(EmailAccount account, String gmailMessageId, boolean retryOnAuthFailure) {
        try {
            Gmail gmail = getGmailService(account);
            return gmail.users().messages().get("me", gmailMessageId).execute();
        } catch (GoogleJsonResponseException e) {
            if (retryOnAuthFailure && e.getStatusCode() == 401) {
                if (googleTokenService.refreshAccessToken(account) != null) {
                    return getMessageInternal(account, gmailMessageId, false);
                }
            }
            log.error("Failed to fetch Gmail message {} for {}: {}", gmailMessageId, account.getEmailAddress(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to fetch Gmail message {} for {}: {}", gmailMessageId, account.getEmailAddress(), e.getMessage());
            return null;
        }
    }

    private Gmail getGmailService(EmailAccount account) throws GeneralSecurityException, IOException {
        String accessToken = encryptionService.decrypt(account.getEncryptedPassword());

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                request -> {
                    credential.initialize(request);
                    request.setConnectTimeout(10000); // 10 seconds
                    request.setReadTimeout(10000);    // 10 seconds
                })
                .setApplicationName("Email Client AI")
                .build();
    }
}
