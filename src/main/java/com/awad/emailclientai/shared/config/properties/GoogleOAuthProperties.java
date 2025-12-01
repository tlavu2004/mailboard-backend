package com.awad.emailclientai.shared.config.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Google OAuth configuration properties
 * Maps to google.oauth.* properties
 */
@Configuration
@ConfigurationProperties(prefix = "google.oauth")
@Getter
@Setter
public class GoogleOAuthProperties {

    private Boolean enabled = false;

    @NotBlank(message = "Google OAuth clientId must be specified")
    private String clientId;

    private String clientSecret;

    private String redirectUri;

    /**
     * Check if Google OAuth is enabled and properly configured
     *
     * @return true if enabled and client ID is set
     */
    public boolean isConfigured() {
        return Boolean.TRUE.equals(enabled) &&
                clientId != null &&
                !clientId.trim().isEmpty();
    }
}