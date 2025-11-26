package com.awad.emailclientai.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-wide configuration beans.
 * Example: JWT service, common utilities, etc.
 */
@Configuration
public class AppConfig {

    // Example bean
    @Bean
    public String exampleBean() {
        return "Hello from AppConfig!";
    }

    // TODO: Add other shared beans like JWTService, EmailService, etc.
}
