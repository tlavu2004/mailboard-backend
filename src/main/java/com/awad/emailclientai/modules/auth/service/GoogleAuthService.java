package com.awad.emailclientai.modules.auth.service;

import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.repository.UserRepository;
import com.awad.emailclientai.shared.config.properties.GoogleOAuthProperties;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleAuthService {

    private final UserRepository userRepository;
    private final GoogleOAuthProperties googleOAuthProperties;

    @Transactional
    public User authenticateGoogleUser(String idTokenString) {
        try {
            List<String> allowedAudiences = new ArrayList<>();
            allowedAudiences.add(googleOAuthProperties.getClientId());
            
            String playgroundId = googleOAuthProperties.getPlaygroundClientId();
            if (playgroundId != null && !playgroundId.isBlank()) {
                allowedAudiences.add(playgroundId);
            }

            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance()
            )
                    .setAudience(allowedAudiences)
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken != null) {
                GoogleIdToken.Payload payload = idToken.getPayload();

                String googleId = payload.getSubject();
                String email = payload.getEmail();
                String name = (String) payload.get("name");

                return userRepository.findByGoogleId(googleId)
                        .orElseGet(() -> {
                            User newUser = User.builder()
                                    .email(email)
                                    .googleId(googleId)
                                    .authProvider(com.awad.emailclientai.modules.user.entity.AuthProvider.GOOGLE)
                                    .name(name)
                                    .password(null)
                                    .build();
                            return userRepository.save(newUser);
                        });
            } else {
                // Log the token's audience and issuer to find mismatch
                try {
                    GoogleIdToken unsafeToken = GoogleIdToken.parse(GsonFactory.getDefaultInstance(), idTokenString);
                    GoogleIdToken.Payload payload = unsafeToken.getPayload();
                    log.error("GoogleIdTokenVerifier returned null. Token details (UNVERIFIED): \n" +
                             "  - Issuer (iss): {}\n" +
                             "  - Audience (aud): {}\n" +
                             "  - Email: {}\n" +
                             "  - Expiration (exp): {}\n" +
                             "  - Current server time (s): {}", 
                             payload.getIssuer(), payload.getAudience(), payload.getEmail(), 
                             payload.getExpirationTimeSeconds(), System.currentTimeMillis() / 1000);
                } catch (Exception e) {
                    log.error("Failed to parse invalid token for debugging: {}", e.getMessage());
                }
                throw new RuntimeException("Invalid Google ID token (verifier returned null)");
            }
        } catch (Exception e) {
            log.error("Google authentication failed: {} - Token start: {}", e.getMessage(), 
                     (idTokenString != null && idTokenString.length() > 20) ? idTokenString.substring(0, 20) : "null", e);
            throw new RuntimeException("Google authentication failed: " + e.getMessage());
        }
    }
}