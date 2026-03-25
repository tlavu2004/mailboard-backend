package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.shared.config.properties.GoogleOAuthProperties;
import com.awad.emailclientai.shared.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleTokenService {

    private final EmailAccountRepository accountRepository;
    private final EncryptionService encryptionService;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    @Transactional
    public String refreshAccessToken(EmailAccount account) {
        String refreshToken = encryptionService.decrypt(account.getEncryptedRefreshToken());
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("No refresh token found for account: {}", account.getEmailAddress());
            return null;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = String.format("client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token",
                    googleOAuthProperties.getClientId(),
                    googleOAuthProperties.getClientSecret(),
                    refreshToken);

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response = restTemplate.postForEntity(
                    "https://oauth2.googleapis.com/token", entity, (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String newAccessToken = (String) response.getBody().get("access_token");
                account.setEncryptedPassword(encryptionService.encrypt(newAccessToken));
                accountRepository.save(account);
                log.info("Successfully refreshed access token for: {}", account.getEmailAddress());
                return newAccessToken;
            }
        } catch (Exception e) {
            log.error("Failed to refresh token for {}: {}", account.getEmailAddress(), e.getMessage());
        }
        return null;
    }
}
