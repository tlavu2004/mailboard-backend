package com.awad.emailclientai.shared.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Refresh Token Configuration Properties
 *
 * <p>Manages refresh token settings including expiration time and storage strategy.
 * <br>Refresh tokens are long-lived tokens used to obtain new access tokens without
 * requiring the user to re-authenticate.
 *
 * <p><b>Configuration Example (application.yml):</b>
 * <pre>
 * refresh-token:
 *   expiration-days: 30
 *   expiration-ms: 2592000000  # 30 days in milliseconds
 *   storage-type: DATABASE      # DATABASE or REDIS
 *   cookie-name: refresh_token
 *   cookie-http-only: true
 *   cookie-secure: true         # Set to true in production (HTTPS only)
 *   cookie-same-site: Strict
 *   rotation-enabled: true      # Enable refresh token rotation for better security
 * </pre>
 *
 * <p><b>Security Best Practices:</b>
 * <ul>
 *   <li><b>Storage:</b> Store in HttpOnly cookie (preferred) or secure localStorage</li>
 *   <li><b>Expiration:</b> 7-30 days (longer than access token, shorter than session)</li>
 *   <li><b>Rotation:</b> Generate new refresh token on each use (prevents replay attacks)</li>
 *   <li><b>Revocation:</b> Store in database to enable manual revocation</li>
 *   <li><b>One-time use:</b> Invalidate old refresh token after generating new one</li>
 * </ul>
 *
 * <p><b>Assignment Requirements:</b>
 * <ul>
 *   <li>Refresh tokens should be stored persistently (localStorage or HttpOnly cookie)</li>
 *   <li>Access tokens should be stored in-memory (session storage)</li>
 *   <li>Implement automatic token refresh on 401 responses</li>
 *   <li>Handle concurrent refresh requests (only one refresh at a time)</li>
 * </ul>
 *
 * @see JwtProperties
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "refresh-token")
@Validated
public class RefreshTokenProperties {

    /**
     * Refresh token expiration time in days
     * <br>Default: 30 days
     * <br>Range: 1-365 days
     *
     * <p><b>Recommendations:</b>
     * <ul>
     *   <li><b>7 days:</b> High security applications</li>
     *   <li><b>30 days:</b> Standard applications (recommended)</li>
     *   <li><b>90 days:</b> Low security, high convenience applications</li>
     * </ul>
     */
    @NotNull(message = "Refresh token expiration days must be specified")
    @Min(value = 1, message = "Refresh token expiration days must be at least 1")
    private Integer expirationDays = 30;

    /**
     * Refresh token expiration time in milliseconds
     * <br>Default: 2,592,000,000 ms (30 days)
     * <br>Calculated from expirationDays if not explicitly set
     */
    @NotNull(message = "Refresh token expiration milliseconds must be specified")
    @Min(value = 86400000, message = "Refresh token expiration must be at least 1 day")
    private Long expirationMs = 2592000000L; // 30 days

    /**
     * Storage strategy for refresh tokens
     * <br>Options: DATABASE, REDIS, MEMORY
     *
     * <p><b>DATABASE:</b>
     * <ul>
     *   <li>Pros: Persistent, survives server restarts, easy revocation</li>
     *   <li>Cons: Database dependency, slightly slower</li>
     *   <li>Use case: Production environments, multi-server deployments</li>
     * </ul>
     *
     * <p><b>REDIS:</b>
     * <ul>
     *   <li>Pros: Fast, automatic expiration, good for high traffic</li>
     *   <li>Cons: Requires Redis server, data lost on Redis restart</li>
     *   <li>Use case: High-traffic production environments</li>
     * </ul>
     *
     * <p><b>MEMORY:</b>
     * <ul>
     *   <li>Pros: Fastest, no external dependencies</li>
     *   <li>Cons: Lost on server restart, not for multi-server</li>
     *   <li>Use case: Development, single-server environments</li>
     * </ul>
     */
    @NotBlank(message = "Storage type must be specified")
    private String storageType = "DATABASE";

    /**
     * Cookie name for storing refresh token
     * <br>Default: "refresh_token"
     * <br>Used when storing refresh token in HttpOnly cookie
     */
    @NotBlank(message = "Cookie name must be specified")
    private String cookieName = "refresh_token";

    /**
     * Enable HttpOnly flag for refresh token cookie
     * <br>Default: true (recommended)
     *
     * <p><b>HttpOnly:</b>
     * <ul>
     *   <li>Prevents JavaScript access to cookie (XSS protection)</li>
     *   <li>Only accessible via HTTP requests</li>
     *   <li><b>Should always be true in production</b></li>
     * </ul>
     */
    @NotNull(message = "Cookie HttpOnly setting must be specified")
    private Boolean cookieHttpOnly = true;

    /**
     * Enable Secure flag for refresh token cookie
     * <br>Default: true (required for production)
     *
     * <p><b>Secure flag:</b>
     * <ul>
     *   <li>Cookie only sent over HTTPS</li>
     *   <li>Prevents man-in-the-middle attacks</li>
     *   <li><b>Must be true in production</b></li>
     *   <li>Set to false only for local development (HTTP)</li>
     * </ul>
     */
    @NotNull(message = "Cookie Secure setting must be specified")
    private Boolean cookieSecure = true;

    /**
     * SameSite attribute for refresh token cookie
     * <br>Default: "Strict"
     * <br>Options: Strict, Lax, None
     *
     * <p><b>SameSite values:</b>
     * <ul>
     *   <li><b>Strict:</b> Most secure, cookie not sent on cross-site requests</li>
     *   <li><b>Lax:</b> Moderate, cookie sent on top-level navigation (GET only)</li>
     *   <li><b>None:</b> Cookie sent on all requests (requires Secure=true)</li>
     * </ul>
     *
     * <p><b>Recommendation:</b> Use "Strict" for same-origin apps, "Lax" for cross-origin
     */
    @NotBlank(message = "Cookie SameSite setting must be specified")
    private String cookieSameSite = "Strict";

    /**
     * Cookie max age in seconds
     * <br>Default: 2,592,000 seconds (30 days)
     * <br>Should match expirationMs
     */
    @NotNull(message = "Cookie max age must be specified")
    @Min(value = 86400, message = "Cookie max age must be at least 1 day")
    private Integer cookieMaxAge = 2592000; // 30 days

    /**
     * Cookie path
     * <br>Default: "/api/v1/auth"
     * <br>Restricts cookie to authentication endpoints only
     *
     * <p><b>Best practice:</b> Limit cookie scope to minimize exposure
     */
    @NotBlank(message = "Cookie path must be specified")
    private String cookiePath = "/api/v1/auth";

    /**
     * Enable refresh token rotation
     * <br>Default: true (recommended for security)
     *
     * <p><b>Token Rotation:</b>
     * <ul>
     *   <li>Generate new refresh token on each use</li>
     *   <li>Invalidate old refresh token immediately</li>
     *   <li>Prevents token replay attacks</li>
     *   <li>Detects token theft (concurrent use triggers logout)</li>
     * </ul>
     *
     * <p><b>Assignment Requirement:</b>
     * <br>This is a stretch goal - "Silent token refresh before expiration"
     */
    @NotNull(message = "Token rotation setting must be specified")
    private Boolean rotationEnabled = true;

    /**
     * Maximum number of active refresh tokens per user
     * <br>Default: 5 (allows multiple devices/browsers)
     *
     * <p><b>Use cases:</b>
     * <ul>
     *   <li><b>1:</b> Single device login only</li>
     *   <li><b>5:</b> Multiple devices (recommended)</li>
     *   <li><b>-1:</b> Unlimited (not recommended)</li>
     * </ul>
     */
    @NotNull(message = "Max tokens per user must be specified")
    @Min(value = -1, message = "Max tokens per user must be -1 (unlimited) or positive")
    private Integer maxTokensPerUser = 5;

    /**
     * Enable automatic cleanup of expired tokens
     * <br>Default: true
     *
     * <p><b>Cleanup strategy:</b>
     * <ul>
     *   <li>Scheduled job runs daily</li>
     *   <li>Removes tokens older than expiration time</li>
     *   <li>Keeps database clean and performant</li>
     * </ul>
     */
    @NotNull(message = "Auto cleanup setting must be specified")
    private Boolean autoCleanupEnabled = true;

    /**
     * Cleanup schedule cron expression
     * <br>Default: "0 0 2 * * ?" (every day at 2 AM)
     */
    @NotBlank(message = "Cleanup schedule must be specified")
    private String cleanupSchedule = "0 0 2 * * ?";

    /**
     * Get expiration time in milliseconds
     * <br>Convenience method for token generation
     *
     * @return expiration time in milliseconds
     */
    public long getExpirationMs() {
        return expirationMs != null ? expirationMs : (expirationDays * 24L * 60 * 60 * 1000);
    }

    /**
     * Check if database storage is enabled
     *
     * @return true if storage type is DATABASE
     */
    public boolean isDatabaseStorage() {
        return "DATABASE".equalsIgnoreCase(storageType);
    }

    /**
     * Check if Redis storage is enabled
     *
     * @return true if storage type is REDIS
     */
    public boolean isRedisStorage() {
        return "REDIS".equalsIgnoreCase(storageType);
    }

    /**
     * Check if memory storage is enabled
     *
     * @return true if storage type is MEMORY
     */
    public boolean isMemoryStorage() {
        return "MEMORY".equalsIgnoreCase(storageType);
    }
}
