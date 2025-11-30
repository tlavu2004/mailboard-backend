package com.awad.emailclientai.shared.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT Authentication Filter
 *
 * <p>Intercepts every HTTP request to validate JWT tokens and set authentication context.
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>Extract JWT token from Authorization header (Bearer token)</li>
 *   <li>Validate token and extract username (email) using JwtService</li>
 *   <li>Load user details from database via UserDetailsService</li>
 *   <li>Verify token validity against user details</li>
 *   <li>Set authentication in SecurityContext for the current request</li>
 * </ol>
 *
 * <p><b>Note:</b> This filter runs ONCE per request, before reaching the controller.
 * If JwtService or UserDetailsService is not available, the filter will skip JWT validation
 * and let Spring Security handle authorization (will deny access to protected endpoints).
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // Optional dependencies - will be null if modules not implemented yet
    @Autowired(required = false)
    private JwtService jwtService;

    @Autowired(required = false)
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Skip JWT validation if dependencies not ready
        if (jwtService == null || userDetailsService == null) {
            log.trace("JWT validation skipped - JwtService or UserDetailsService not available");
            filterChain.doFilter(request, response);
            return;
        }

        // 1. Extract Authorization header
        final String authHeader = request.getHeader("Authorization");

        // Skip if no Authorization header or not Bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 2. Extract JWT token (remove "Bearer " prefix)
            final String jwt = authHeader.substring(7);

            // 3. Extract username (email) from token
            final String userEmail = jwtService.extractUsername(jwt);

            // 4. If token has username and user is not authenticated yet
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 5. Load user details from database
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

                // 6. Validate token
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // 7. Create authentication token
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,  // credentials (password) not needed after authentication
                            userDetails.getAuthorities()
                    );

                    // 8. Set request details (IP address, session ID, etc.)
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    // 9. Set authentication in SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("JWT authentication successful for user: {}", userEmail);
                }
            }

        } catch (Exception e) {
            // Log error but don't block the request
            // JwtAuthenticationEntryPoint will handle unauthorized access
            log.error("JWT authentication failed: {}", e.getMessage());
        }

        // 10. Continue filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Interface for JWT Service (to be implemented in auth module)
     * This allows the filter to compile without the actual implementation.
     */
    public interface JwtService {
        String extractUsername(String token);
        boolean isTokenValid(String token, UserDetails userDetails);
    }
}