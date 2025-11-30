package com.awad.emailclientai.shared.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Authentication Entry Point
 *
 * <p>Handles authentication failures and returns standardized JSON error responses
 * instead of redirecting to a login page (default behavior for web apps).
 *
 * <p><b>Triggered when:</b>
 * <ul>
 *   <li>No JWT token provided for protected endpoint</li>
 *   <li>Invalid JWT token format or signature</li>
 *   <li>Expired JWT token</li>
 *   <li>User tries to access protected endpoint without authentication</li>
 *   <li>JWT token validation fails in JwtAuthenticationFilter</li>
 * </ul>
 *
 * <p><b>Response Format (Temporary):</b><br>
 * Currently returns a simple error map. Will be replaced with standardized
 * {@code ApiResponse<T>} format once the shared DTO is implemented.
 *
 * <p><b>Future Format:</b>
 * <pre>
 * {
 *   "success": false,
 *   "message": "Authentication required",
 *   "data": null,
 *   "errorCode": "AUTH_001",
 *   "timestamp": "2025-01-15T10:30:00"
 * }
 * </pre>
 *
 * @author AWAD Team
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {

        log.error("Unauthorized access attempt: {} - Path: {}",
                authException.getMessage(), request.getRequestURI());

        // Set response status to 401 Unauthorized
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // TODO: Replace with ApiResponse<Void> when available
        // ApiResponse<Void> errorResponse = ApiResponse.<Void>builder()
        //     .success(false)
        //     .message("Authentication required")
        //     .errorCode(ErrorCode.UNAUTHORIZED.getCode())
        //     .timestamp(LocalDateTime.now())
        //     .build();

        // Temporary error response format
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", 401);
        errorResponse.put("error", "Unauthorized");
        errorResponse.put("message", getAuthenticationErrorMessage(authException));
        errorResponse.put("path", request.getRequestURI());

        // Write JSON response
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    /**
     * Extract user-friendly error message from authentication exception
     *
     * @param authException the authentication exception
     * @return user-friendly error message
     */
    private String getAuthenticationErrorMessage(AuthenticationException authException) {
        String message = authException.getMessage();

        // Provide generic message if exception message is too technical
        if (message == null || message.isEmpty()) {
            return "Authentication is required to access this resource";
        }

        // Map common exceptions to user-friendly messages
        if (message.contains("Bad credentials")) {
            return "Invalid email or password";
        } else if (message.contains("expired")) {
            return "Your session has expired. Please login again";
        } else if (message.contains("disabled")) {
            return "Account is disabled. Please contact support";
        } else if (message.contains("locked")) {
            return "Account is locked. Please contact support";
        }

        // Return original message if no mapping found
        return "Authentication failed: " + message;
    }
}