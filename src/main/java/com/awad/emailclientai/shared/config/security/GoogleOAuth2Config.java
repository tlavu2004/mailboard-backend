package com.awad.emailclientai.shared.config.security;

import com.awad.emailclientai.shared.config.properties.GoogleOAuthProperties;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Google OAuth2 Configuration
 *
 * <p>Configures Google OAuth2 authentication and token verification.
 * <br>Provides beans for validating Google ID tokens received from the frontend.
 *
 * <p><b>OAuth2 Flow (Assignment Requirement):</b>
 * <ol>
 *   <li><b>Frontend:</b> User clicks "Sign in with Google" button</li>
 *   <li><b>Frontend:</b> Google OAuth popup opens, user authenticates</li>
 *   <li><b>Frontend:</b> Receives Google ID token (JWT)</li>
 *   <li><b>Frontend:</b> Sends ID token to backend: POST /api/v1/auth/google</li>
 *   <li><b>Backend:</b> Validates ID token using GoogleIdTokenVerifier</li>
 *   <li><b>Backend:</b> Extracts user info (email, name, picture)</li>
 *   <li><b>Backend:</b> Creates or updates user in database</li>
 *   <li><b>Backend:</b> Generates app's own access + refresh tokens</li>
 *   <li><b>Backend:</b> Returns tokens to frontend</li>
 * </ol>
 *
 * <p><b>Security Notes:</b>
 * <ul>
 *   <li>Never trust the frontend - always verify Google tokens on backend</li>
 *   <li>Check token audience (client ID) matches your app</li>
 *   <li>Check token issuer is Google (accounts.google.com)</li>
 *   <li>Check token hasn't expired</li>
 *   <li>Don't use Google tokens as session tokens - generate your own JWT</li>
 * </ul>
 *
 * <p><b>Configuration (application.yml):</b>
 * <pre>
 * google:
 *   oauth:
 *     enabled: true
 *     client-id: your-client-id.apps.googleusercontent.com
 *     client-secret: your-client-secret
 *     redirect-uri: <a href="http://localhost:3000/auth/google/callback">...</a>
 * </pre>
 *
 * <p><b>Frontend Integration (React):</b>
 * <pre>
 * // Install: npm install @react-oauth/google
 *
 * import { GoogleOAuthProvider, GoogleLogin } from '@react-oauth/google';
 *
 * function LoginPage() {
 *   const handleGoogleSuccess = async (credentialResponse) => {
 *     const { credential } = credentialResponse; // This is the ID token
 *
 *     const response = await fetch('/api/v1/auth/google', {
 *       method: 'POST',
 *       headers: { 'Content-Type': 'application/json' },
 *       body: JSON.stringify({ idToken: credential })
 *     });
 *
 *     const data = await response.json();
 *     // Store access token in memory, refresh token in localStorage
 *   };
 *
 *   return (
 *     &lt;GoogleOAuthProvider clientId="your-client-id"&gt;
 *       &lt;GoogleLogin
 *         onSuccess={handleGoogleSuccess}
 *         onError={() => console.log('Login Failed')}
 *       /&gt;
 *     &lt;/GoogleOAuthProvider&gt;
 *   );
 * }
 * </pre>
 *
 * @author AWAD Team
 * @since 1.0
 * @see GoogleOAuthProperties
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "google.oauth",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class GoogleOAuth2Config {

    private final GoogleOAuthProperties googleOAuthProperties;

    /**
     * Creates a GoogleIdTokenVerifier bean for validating Google ID tokens
     *
     * <p><b>Purpose:</b>
     * <br>Verifies that the ID token received from frontend is:
     * <ul>
     *   <li>Actually issued by Google (not forged)</li>
     *   <li>Intended for this application (audience check)</li>
     *   <li>Not expired</li>
     *   <li>Has valid signature</li>
     * </ul>
     *
     * <p><b>Usage in Service:</b>
     * <pre>
     * {@literal @}Service
     * {@literal @}RequiredArgsConstructor
     * public class GoogleAuthService {
     *     private final GoogleIdTokenVerifier verifier;
     *
     *     public GoogleIdToken.Payload verifyToken(String idTokenString) {
     *         try {
     *             GoogleIdToken idToken = verifier.verify(idTokenString);
     *
     *             if (idToken == null) {
     *                 throw new UnauthorizedException(ErrorCode.INVALID_GOOGLE_TOKEN);
     *             }
     *
     *             GoogleIdToken.Payload payload = idToken.getPayload();
     *
     *             // Extract user info
     *             String email = payload.getEmail();
     *             String name = (String) payload.get("name");
     *             String pictureUrl = (String) payload.get("picture");
     *             boolean emailVerified = payload.getEmailVerified();
     *
     *             if (!emailVerified) {
     *                 throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
     *             }
     *
     *             return payload;
     *
     *         } catch (GeneralSecurityException | IOException e) {
     *             log.error("Failed to verify Google ID token", e);
     *             throw new UnauthorizedException(
     *                 ErrorCode.INVALID_GOOGLE_TOKEN,
     *                 "Google authentication failed",
     *                 e
     *             );
     *         }
     *     }
     * }
     * </pre>
     *
     * <p><b>How it works:</b>
     * <ol>
     *   <li>Downloads Google's public keys from <a href="https://www.googleapis.com/oauth2/v3/certs">...</a></li>
     *   <li>Caches keys for performance (automatically refreshed)</li>
     *   <li>Verifies token signature using public key cryptography</li>
     *   <li>Checks audience (client ID) and issuer</li>
     *   <li>Validates expiration time</li>
     * </ol>
     *
     * @return configured GoogleIdTokenVerifier instance
     */
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier() {
        log.info("Initializing GoogleIdTokenVerifier with client ID: {}",
                maskClientId(googleOAuthProperties.getClientId()));

        return new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),  // HTTP transport for making requests
                GsonFactory.getDefaultInstance()  // JSON factory for parsing
        )
                // Set the client ID(s) that this verifier accepts
                // Can add multiple client IDs if you have web + mobile apps
                .setAudience(Collections.singletonList(googleOAuthProperties.getClientId()))

                // Optional: Set issuer validation (default is already correct)
                // .setIssuer("accounts.google.com")
                .build();
    }

    /**
     * Masks client ID for logging (security best practice)
     * <br>Example: "123456789-abc...xyz.apps.googleusercontent.com" → "123...xyz.apps.googleusercontent.com"
     *
     * @param clientId the full client ID
     * @return masked client ID
     */
    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 10) {
            return "***";
        }
        return clientId.substring(0, 3) + "..." + clientId.substring(clientId.length() - 3);
    }

    /**
     * Bean post-processor to validate Google OAuth configuration on startup
     * <br>Ensures all required properties are set before app starts
     */
    @Bean
    public GoogleOAuthConfigValidator googleOAuthConfigValidator() {
        return new GoogleOAuthConfigValidator(googleOAuthProperties);
    }

    /**
     * Validates Google OAuth configuration
     * <br>Fails fast if configuration is invalid
     */
    public static class GoogleOAuthConfigValidator {
        private final GoogleOAuthProperties properties;

        public GoogleOAuthConfigValidator(GoogleOAuthProperties properties) {
            this.properties = properties;
            validate();
        }

        private void validate() {
            if (properties.getClientId() == null || properties.getClientId().trim().isEmpty()) {
                throw new IllegalStateException(
                        "google.oauth.client-id must be configured when Google OAuth is enabled"
                );
            }

            if (!properties.getClientId().endsWith(".apps.googleusercontent.com")) {
                log.warn("Google OAuth client ID format seems incorrect. " +
                        "Expected format: *.apps.googleusercontent.com");
            }

            log.info("✓ Google OAuth configuration validated successfully");
        }
    }

    /**
     * Optional: Bean for making requests to Google APIs (user info, etc.)
     * <br>Uncomment if you need to fetch additional user data from Google
     */
    /*
    @Bean
    public RestTemplate googleApiRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // Add interceptor to log requests (optional)
        restTemplate.getInterceptors().add((request, body, execution) -> {
            log.debug("Google API Request: {} {}", request.getMethod(), request.getURI());
            ClientHttpResponse response = execution.execute(request, body);
            log.debug("Google API Response: {}", response.getStatusCode());
            return response;
        });

        return restTemplate;
    }
    */
}