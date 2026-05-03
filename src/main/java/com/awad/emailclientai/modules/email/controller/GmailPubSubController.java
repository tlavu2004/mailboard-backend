package com.awad.emailclientai.modules.email.controller;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.email.service.EmailSyncService;
import com.awad.emailclientai.modules.email.service.ImapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;




@RestController
@RequestMapping("/api/v1/public/gmail")
@RequiredArgsConstructor
@Slf4j
public class GmailPubSubController {

    private final EmailAccountRepository accountRepository;
    private final EmailSyncService emailSyncService;
    private final ImapService imapService;
    private final ObjectMapper objectMapper;
    private final Executor mailSyncExecutor;

    @Value("${app.mail.sync.batch-size:20}")
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
                
                // Trigger sync in a managed thread pool (non-blocking for Google)
                mailSyncExecutor.execute(() -> {
                    try {
                        // Use efficient Gmail History API to find exactly what changed (Labels, Moves, etc.)
                        try {
                            emailSyncService.syncEmailsByHistory(account, historyId);
                        } catch (Exception historyEx) {
                            log.warn("History sync failed for {}: {}. Falling back to folder sync.", emailAddress, historyEx.getMessage());
                            
                            // Fallback: Sync key system folders so label moves (e.g. Gmail delete -> TRASH)
                            // are reflected back to local status promptly.
                            String physicalTrash = imapService.findPhysicalFolderByType(account, "TRASH");
                            String physicalSpam = imapService.findPhysicalFolderByType(account, "SPAM");
                            List<String> foldersToSync = List.of("INBOX", physicalTrash, physicalSpam);
                            
                            // Deduplicate in case localized fallback matched INBOX
                            foldersToSync = foldersToSync.stream().distinct().toList();
                            
                            log.info("Resolving physical folders to sync for webhook on {}: {}", emailAddress, foldersToSync);
                            
                            for (String folder : foldersToSync) {
                                try {
                                    emailSyncService.syncEmailsForAccount(account.getId(), folder, batchSize, 0);
                                } catch (Exception folderEx) {
                                    log.warn("Triggered sync failed for {} folder {}: {}", emailAddress, folder, folderEx.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error during triggered sync for {}", emailAddress, e);
                    }
                });
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