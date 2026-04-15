package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.modules.email.service.NotificationWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/public/gmail")
@RequiredArgsConstructor
@Slf4j
public class GmailPubSubController {

    private final EmailAccountRepository accountRepository;
    private final EmailSyncService emailSyncService;
    private final NotificationWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.mail.sync.batch-size:20}")
    private int batchSize;

    /**
     * Webhook endpoint to receive push notifications from Google Cloud Pub/Sub.
     * Google sends a POST request with a "message" field containing base64 encoded data.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(@RequestBody JsonNode payload) {
        log.debug("Received Gmail Pub/Sub webhook: {}", payload);

        try {
            JsonNode message = payload.get("message");
            if (message == null || !message.has("data")) {
                log.warn("Invalid Pub/Sub payload: missing message or data");
                return ResponseEntity.ok().build();
            }

            String base64Data = message.get("data").asText();
            byte[] decodedBytes = Base64.getDecoder().decode(base64Data);
            JsonNode data = objectMapper.readTree(decodedBytes);

            String emailAddress = data.get("emailAddress").asText();
            Long historyId = data.get("historyId").asLong();

            log.info("Received push notification for {}. History ID: {}", emailAddress, historyId);

            Optional<EmailAccount> accountOpt = accountRepository.findByEmailAddress(emailAddress);
            if (accountOpt.isPresent()) {
                EmailAccount account = accountOpt.get();
                
                // Trigger sync in a separate thread (non-blocking for Google)
                new Thread(() -> {
                    try {
                        emailSyncService.syncEmailsForAccount(account.getId(), "INBOX", batchSize, 0);

                        // Notify frontend via WebSocket using typed payload so FE can react
                        webSocketHandler.sendNotification(account.getId(), "NEW_EMAILS", "Sync completed for " + emailAddress);
                    } catch (Exception e) {
                        log.error("Error during triggered sync for {}", emailAddress, e);
                    }
                }).start();
            } else {
                log.warn("Received notification for unknown account: {}", emailAddress);
            }

        } catch (Exception e) {
            log.error("Error processing Gmail Pub/Sub webhook", e);
        }

        // Always return 200 OK to acknowledge receipt to Google
        return ResponseEntity.ok().build();
    }
}
