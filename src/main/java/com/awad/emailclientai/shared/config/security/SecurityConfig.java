package com.awad.emailclientai.shared.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Main Security Configuration
 * Configures JWT authentication, CORS, and endpoint security
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // These will be created later in auth module
    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtAuthEntryPoint;
    private final PasswordEncoder passwordEncoder;
    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Configure HTTP security
     * - Disable CSRF (using JWT tokens)
     * - Enable CORS
     * - Configure public and protected endpoints
     * - Add JWT filter
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF (not needed for stateless JWT authentication)
                .csrf(AbstractHttpConfigurer::disable)

                // Enable CORS with custom configuration
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // Configure endpoint authorization
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (authentication)
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/public/**",
                                "/error",
                                "/health"
                        ).permitAll()

                        // All other endpoints require authentication
                        .anyRequest().authenticated()
                )

                // Stateless session management (no server-side sessions)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Exception handling for unauthorized requests
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(jwtAuthEntryPoint)
                )

                // Add JWT filter before UsernamePasswordAuthenticationFilter
                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * Authentication manager bean
     * Used for manual authentication (e.g., in login endpoint)
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }
}