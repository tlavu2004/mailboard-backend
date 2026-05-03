package com.awad.emailclientai.modules.email.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.shared.config.properties.GoogleOAuthProperties;
import com.awad.emailclientai.shared.service.EncryptionService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;





@Service
@Slf4j
public class GoogleTokenService {

    private final EmailAccountRepository accountRepository;
    private final EncryptionService encryptionService;
    private final GoogleOAuthProperties googleOAuthProperties;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;

    public GoogleTokenService(EmailAccountRepository accountRepository, 
                             EncryptionService encryptionService, 
                             GoogleOAuthProperties googleOAuthProperties,
                             TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.encryptionService = encryptionService;
        this.googleOAuthProperties = googleOAuthProperties;
        this.transactionTemplate = transactionTemplate;
        
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 5 seconds
        factory.setReadTimeout(5000);    // 5 seconds
        this.restTemplate = new RestTemplate(factory);
    }

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
                String encryptedToken = encryptionService.encrypt(newAccessToken);
                
                transactionTemplate.execute(status -> {
                    // Re-fetch to avoid detached entity if needed, but since it's usually passed from service it might be fine.
                    // However, for safety and to keep transaction short:
                    EmailAccount activeAccount = accountRepository.findById(account.getId()).orElse(account);
                    activeAccount.setEncryptedPassword(encryptedToken);
                    accountRepository.save(activeAccount);
                    return null;
                });
                
                log.info("Successfully refreshed access token for: {}", account.getEmailAddress());
                return newAccessToken;
            }
        } catch (Exception e) {
            log.error("Failed to refresh token for {}: {}", account.getEmailAddress(), e.getMessage());
        }
        return null;
    }
}