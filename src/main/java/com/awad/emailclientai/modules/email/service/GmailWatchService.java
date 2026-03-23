package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailProvider;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.shared.service.EncryptionService;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.WatchRequest;
import com.google.api.services.gmail.model.WatchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GmailWatchService {

    private final EmailAccountRepository accountRepository;
    private final EncryptionService encryptionService;
    
    @Value("${gmail.pubsub.topic}")
    private String topicName;

    /**
     * Registers a watch on the Gmail inbox for the given account.
     */
    @Transactional
    public void watchInbox(EmailAccount account) {
        if (account.getProvider() != EmailProvider.GMAIL) {
            return;
        }

        try {
            Gmail gmail = getGmailService(account);
            WatchRequest request = new WatchRequest()
                    .setTopicName(topicName)
                    .setLabelIds(List.of("INBOX"));

            WatchResponse response = gmail.users().watch("me", request).execute();
            
            account.setWatchExpiration(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(response.getExpiration()), ZoneId.systemDefault()));
            account.setWatchHistoryId(response.getHistoryId().longValue());
            accountRepository.save(account);
            
            log.info("Gmail watch registered for {}. Expires at: {}", 
                    account.getEmailAddress(), account.getWatchExpiration());
            
        } catch (Exception e) {
            log.error("Failed to register Gmail watch for {}: {}", account.getEmailAddress(), e.getMessage());
            account.setLastError("Gmail Watch error: " + e.getMessage());
            accountRepository.save(account);
        }
    }

    /**
     * Stop watching Gmail inbox.
     */
    public void stopWatch(EmailAccount account) {
        try {
            Gmail gmail = getGmailService(account);
            gmail.users().stop("me").execute();
            
            account.setWatchExpiration(null);
            accountRepository.save(account);
            log.info("Gmail watch stopped for {}", account.getEmailAddress());
        } catch (Exception e) {
            log.error("Failed to stop Gmail watch for {}: {}", account.getEmailAddress(), e.getMessage());
        }
    }

    /**
     * Scheduled task to renew Gmail watches before they expire.
     * Gmail watches expire after 7 days. We renew them every 6 hours if they expire within 1 day.
     */
    @Scheduled(fixedRate = 21600000, initialDelay = 10000) // 6 hours, starts after 10s
    @Transactional
    public void renewWatches() {
        LocalDateTime threshold = LocalDateTime.now().plusDays(1);
        List<EmailAccount> accountsToRenew = accountRepository.findAll().stream()
                .filter(a -> a.getProvider() == EmailProvider.GMAIL && a.getActive())
                .filter(a -> a.getWatchExpiration() == null || a.getWatchExpiration().isBefore(threshold))
                .toList();

        for (EmailAccount account : accountsToRenew) {
            log.info("Renewing Gmail watch for {}", account.getEmailAddress());
            watchInbox(account);
        }
    }

    private Gmail getGmailService(EmailAccount account) throws GeneralSecurityException, IOException {
        String accessToken = encryptionService.decrypt(account.getEncryptedPassword());
        
        com.google.api.client.auth.oauth2.Credential credential = new com.google.api.client.auth.oauth2.Credential(
                com.google.api.client.auth.oauth2.BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                credential)
                .setApplicationName("Email Client AI")
                .build();
    }
}
