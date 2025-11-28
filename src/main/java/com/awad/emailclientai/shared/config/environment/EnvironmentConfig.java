package com.awad.emailclientai.shared.config.environment;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.Nonnull;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Load environment variables from .env file
 * and inject into Spring Environment before context starts.
 * This allows using ${VAR_NAME} in application.yml
 */
public class EnvironmentConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(@Nonnull ConfigurableApplicationContext applicationContext) {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .load();

            Map<String, Object> envMap = new HashMap<>();
            dotenv.entries().forEach(
                    entry -> envMap.put(
                            entry.getKey(),
                            entry.getValue()
                    )
            );

            ConfigurableEnvironment environment = applicationContext.getEnvironment();
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(
                            "dotenvProperties",
                            envMap
                    ));

            System.out.println("Environment variables loaded from .env successfully!");
        } catch (Exception e) {
            System.err.println("Warning: Could not load .env file. Using defaults from application.yaml");
        }
    }
}
