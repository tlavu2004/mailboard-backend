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

            ListMessagesResponse response = gmail.users().messages().list("me").setQ(query).setMaxResults(10L).execute();
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

    private Gmail getGmailService(EmailAccount account) throws GeneralSecurityException, IOException {
        String accessToken = encryptionService.decrypt(account.getEncryptedPassword());

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Email Client AI")
                .build();
    }
}
