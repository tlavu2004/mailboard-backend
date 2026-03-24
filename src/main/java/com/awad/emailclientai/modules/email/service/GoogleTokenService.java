package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.entity.EmailAuthType;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.shared.config.properties.GoogleOAuthProperties;
import com.awad.emailclientai.shared.service.EncryptionService;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleTokenService {

    private final EmailAccountRepository emailAccountRepository;
    private final EncryptionService encryptionService;
    private final GoogleOAuthProperties googleOAuthProperties;

    @Transactional
    public String refreshAccessToken(EmailAccount account) {
        if (account.getAuthType() != EmailAuthType.OAUTH2) {
            log.debug("Account {} is not using OAuth2, skipping refresh.", account.getEmailAddress());
            return null;
        }

        String refreshTokenEncrypted = account.getEncryptedRefreshToken();
        if (refreshTokenEncrypted == null || refreshTokenEncrypted.isBlank()) {
            log.warn("Refresh token missing for account: {}", account.getEmailAddress());
            return null;
        }

        String refreshToken = encryptionService.decrypt(refreshTokenEncrypted);

        try {
            log.info("Refreshing expired Google access token for: {}", account.getEmailAddress());
            
            TokenResponse response = new GoogleRefreshTokenRequest(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    refreshToken,
                    googleOAuthProperties.getClientId(),
                    googleOAuthProperties.getClientSecret())
                    .execute();

            String newAccessToken = response.getAccessToken();
            if (newAccessToken == null || newAccessToken.isBlank()) {
                throw new RuntimeException("Google returned empty access token");
            }
            
            // Update the account in DB
            account.setEncryptedPassword(encryptionService.encrypt(newAccessToken));
            emailAccountRepository.save(account);
            
            log.info("Successfully refreshed access token for: {}", account.getEmailAddress());
            return newAccessToken;
            
        } catch (Exception e) {
            log.error("Failed to refresh Google access token for {}: {}", 
                    account.getEmailAddress(), e.getMessage());
            account.setLastError("Token refresh failed: " + e.getMessage());
            emailAccountRepository.save(account);
            return null;
        }
    }
}
