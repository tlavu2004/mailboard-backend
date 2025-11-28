package com.awad.emailclientai.shared.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * CORS configuration properties
 * Maps to cors.* properties
 */
@Configuration
@ConfigurationProperties(prefix = "cors")
@Getter
@Setter
public class CorsProperties {

    private List<String> allowedOrigins;
    private List<String> allowedMethods = List.of(
            "GET",
            "POST",
            "PUT",
            "DELETE",
            "PATCH",
            "OPTIONS"
    );
    private List<String> allowedHeaders = List.of(
            "*"
    );
    private List<String> exposedHeaders = List.of(
            "Authorization"
    );
    private Boolean allowCredentials = true;
    private Long maxAge = 3600L;
}