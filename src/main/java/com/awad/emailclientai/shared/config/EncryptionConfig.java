package com.awad.emailclientai.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for encryption service.
 */
@Configuration
public class EncryptionConfig {

    @Value("${encryption.aes-secret-key}")
    private String aesSecretKey;

    public String getAesSecretKey() {
        return aesSecretKey;
    }
}
