package com.awad.emailclientai.shared.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT configuration properties loaded from application.yml
 * Maps to jwt.* properties
 */
@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private Long accessTokenExpiration = 900000L;
    private Long refreshTokenExpiration = 604800000L;
    private String issuer = "email-client-ai";
    private String header = "Authorization";
    private String prefix = "Bearer ";
}