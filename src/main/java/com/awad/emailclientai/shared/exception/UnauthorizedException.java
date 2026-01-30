package com.awad.emailclientai.shared.exception;

import lombok.Getter;

/**
 * Unauthorized Exception
 *
 * <p>Custom exception for authentication and authorization failures.
 * <br>This exception should be thrown when:
 * <ul>
 *   <li>User credentials are invalid</li>
 *   <li>JWT token is expired or invalid</li>
 *   <li>Refresh token is expired or invalid</li>
 *   <li>User lacks required permissions to access a resource</li>
 *   <li>Authentication is required but not provided</li>
 * </ul>
 *
 * <p><b>HTTP Status Codes:</b>
 * <ul>
 *   <li><b>401 Unauthorized:</b> Authentication failed or missing</li>
 *   <li><b>403 Forbidden:</b> Authenticated but insufficient permissions</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b>
 * <pre>
 * // Example 1: Invalid credentials during login
 * if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
 *     throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS);
 * }
 *
 * // Example 2: Expired JWT token
 * if (jwtService.isTokenExpired(token)) {
 *     throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED);
 * }
 *
 * // Example 3: Invalid refresh token
 * if (!refreshTokenService.isValid(refreshToken)) {
 *     throw new UnauthorizedException(
 *         ErrorCode.REFRESH_TOKEN_EXPIRED,
 *         "Please login again to continue"
 *     );
 * }
 *
 * // Example 4: Google OAuth token validation failed
 * try {
 *     googleTokenVerifier.verify(idToken);
 * } catch (GeneralSecurityException e) {
 *     throw new UnauthorizedException(
 *         ErrorCode.INVALID_GOOGLE_TOKEN,
 *         "Google authentication failed",
 *         e
 *     );
 * }
 *
 * // Example 5: Insufficient permissions (403)
 * if (!user.hasRole("ADMIN")) {
 *     throw new UnauthorizedException(ErrorCode.ACCESS_DENIED);
 * }
 * </pre>
 *
 * <p><b>Handling:</b>
 * <br>This exception is automatically caught by {@link GlobalExceptionHandler}
 * and converted to a standardized {@link com.awad.emailclientai.shared.dto.response.ApiResponse} format
 * with appropriate HTTP status code (401 or 403).
 *
 * <p><b>Frontend Behavior:</b>
 * <br>When this exception is thrown:
 * <ul>
 *   <li>Frontend should clear stored tokens</li>
 *   <li>Redirect user to login page</li>
 *   <li>Display the error message to user</li>
 *   <li>For token expiry, may attempt silent refresh first</li>
 * </ul>
 *
 * @see ErrorCode
 * @see GlobalExceptionHandler
 */
@Getter
public class UnauthorizedException extends RuntimeException {

    /**
     * The error code associated with this exception
     * <br>Typically one of: AUTH_001 through AUTH_008
     */
    private final ErrorCode errorCode;

    /**
     * Additional context or details about the authentication failure (optional)
     * <br>Should not contain sensitive information like passwords or tokens
     */
    private final String details;

    /**
     * Creates an unauthorized exception with an error code
     * <br>Uses the error code's default message
     *
     * @param errorCode the authentication error code
     */
    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates an unauthorized exception with an error code and custom message
     * <br>Custom message overrides the error code's default message
     *
     * @param errorCode the authentication error code
     * @param message custom error message
     */
    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates an unauthorized exception with error code, message, and details
     * <br>Details provide extra context while keeping the custom message
     *
     * @param errorCode the authentication error code
     * @param message custom error message
     * @param details additional error details
     */
    public UnauthorizedException(ErrorCode errorCode, String message, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * Creates an unauthorized exception with an error code and root cause
     * <br>Useful for wrapping authentication-related exceptions (e.g., JWT parsing errors)
     *
     * @param errorCode the authentication error code
     * @param message custom error message
     * @param cause the underlying cause
     */
    public UnauthorizedException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates an unauthorized exception with all parameters
     * <br>Most flexible constructor for complex authentication error scenarios
     *
     * @param errorCode the authentication error code
     * @param message custom error message
     * @param details additional error details
     * @param cause the underlying cause
     */
    public UnauthorizedException(ErrorCode errorCode, String message, String details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * Gets the HTTP status code associated with this error
     * <br>Typically 401 (Unauthorized) or 403 (Forbidden)
     *
     * @return HTTP status code
     */
    public int getStatusCode() {
        return errorCode.getStatusCode();
    }

    /**
     * Gets the error code as a string
     *
     * @return error code (e.g., "AUTH_001", "AUTH_002")
     */
    public String getCode() {
        return errorCode.getCode();
    }

    /**
     * Checks if this is a 401 Unauthorized error
     *
     * @return true if status code is 401
     */
    public boolean isUnauthorized() {
        return errorCode.getStatusCode() == 401;
    }

    /**
     * Checks if this is a 403 Forbidden error
     *
     * @return true if status code is 403
     */
    public boolean isForbidden() {
        return errorCode.getStatusCode() == 403;
    }

    @Override
    public String toString() {
        return String.format(
                "UnauthorizedException[code=%s, message=%s, details=%s, statusCode=%d]",
                errorCode.getCode(),
                getMessage(),
                details,
                getStatusCode()
        );
    }
}
