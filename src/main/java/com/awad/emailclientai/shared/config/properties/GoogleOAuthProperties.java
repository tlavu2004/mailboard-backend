package com.awad.emailclientai.shared.config.properties;

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

    private String clientId;
    private String clientSecret;
    private String redirectUri;
}