package com.awad.emailclientai.shared.constant;

/**
 * Application-wide Constants
 *
 * <p>Centralized constants for API paths, headers, authentication, pagination, and more.
 * <br>Using constants instead of magic strings improves maintainability and reduces typos.
 *
 * <p><b>Usage Example:</b>
 * <pre>
 * {@literal @}RestController
 * {@literal @}RequestMapping(ApiConstants.API_V1 + "/users")
 * public class UserController {
 *
 *     {@literal @}GetMapping
 *     public ResponseEntity{@literal <}?{@literal >} getUsers(
 *         {@literal @}RequestParam(defaultValue = ApiConstants.DEFAULT_PAGE) int page
 *     ) { ... }
 * }
 * </pre>
 */
public final class ApiConstants {

    // Prevent instantiation
    private ApiConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }

    // ============= API VERSIONING =============

    /**
     * Base API path with version prefix
     * <br>Example: /api/v1/users, /api/v1/auth/login
     */
    public static final String API_V1 = "/api/v1";

    // ============= AUTHENTICATION ENDPOINTS =============

    /**
     * Base path for all authentication endpoints
     * <br>Full path: /api/v1/auth
     */
    public static final String AUTH_BASE = API_V1 + "/auth";

    /**
     * Login endpoint
     * <br>Full path: /api/v1/auth/login
     */
    public static final String AUTH_LOGIN = AUTH_BASE + "/login";

    /**
     * Register endpoint
     * <br>Full path: /api/v1/auth/register
     */
    public static final String AUTH_REGISTER = AUTH_BASE + "/register";

    /**
     * Google OAuth login endpoint
     * <br>Full path: /api/v1/auth/google
     */
    public static final String AUTH_GOOGLE = AUTH_BASE + "/google";

    /**
     * Refresh token endpoint
     * <br>Full path: /api/v1/auth/refresh
     */
    public static final String AUTH_REFRESH = AUTH_BASE + "/refresh";

    /**
     * Logout endpoint
     * <br>Full path: /api/v1/auth/logout
     */
    public static final String AUTH_LOGOUT = AUTH_BASE + "/logout";

    /**
     * Current user profile endpoint
     * <br>Full path: /api/v1/auth/me
     */
    public static final String AUTH_ME = AUTH_BASE + "/me";

    // ============= USER ENDPOINTS =============

    /**
     * Base path for user management endpoints
     * <br>Full path: /api/v1/users
     */
    public static final String USERS_BASE = API_V1 + "/users";

    // ============= MAILBOX ENDPOINTS =============

    /**
     * Base path for mailbox endpoints
     * <br>Full path: /api/v1/mailboxes
     */
    public static final String MAILBOXES_BASE = API_V1 + "/mailboxes";

    /**
     * Get emails in a mailbox
     * <br>Full path: /api/v1/mailboxes/{mailboxId}/emails
     */
    public static final String MAILBOX_EMAILS = MAILBOXES_BASE + "/{mailboxId}/emails";

    // ============= EMAIL ENDPOINTS =============

    /**
     * Base path for email endpoints
     * <br>Full path: /api/v1/emails
     */
    public static final String EMAILS_BASE = API_V1 + "/emails";

    /**
     * Get email by ID
     * <br>Full path: /api/v1/emails/{emailId}
     */
    public static final String EMAIL_BY_ID = EMAILS_BASE + "/{emailId}";

    // ============= HTTP HEADERS =============

    /**
     * Authorization header name
     * <br>Example: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * Bearer token prefix
     * <br>Example: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * Content-Type header name
     */
    public static final String HEADER_CONTENT_TYPE = "Content-Type";

    /**
     * JSON content type
     */
    public static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * Custom header for refresh token (alternative to cookie)
     * <br>Example: X-Refresh-Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     */
    public static final String HEADER_REFRESH_TOKEN = "X-Refresh-Token";

    // ============= JWT TOKEN CLAIMS =============

    /**
     * JWT claim for user ID
     */
    public static final String CLAIM_USER_ID = "userId";

    /**
     * JWT claim for user email
     */
    public static final String CLAIM_EMAIL = "email";

    /**
     * JWT claim for user roles
     */
    public static final String CLAIM_ROLES = "roles";

    /**
     * JWT claim for token type (access/refresh)
     */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    // ============= TOKEN TYPES =============

    /**
     * Access token type identifier
     */
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";

    /**
     * Refresh token type identifier
     */
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";

    // ============= PAGINATION =============

    /**
     * Default page number (0-indexed)
     */
    public static final String DEFAULT_PAGE = "0";

    /**
     * Default page size
     */
    public static final String DEFAULT_PAGE_SIZE = "20";

    /**
     * Maximum page size allowed
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * Default sort direction
     */
    public static final String DEFAULT_SORT_DIRECTION = "DESC";

    /**
     * Query parameter name for page number
     */
    public static final String PARAM_PAGE = "page";

    /**
     * Query parameter name for page size
     */
    public static final String PARAM_SIZE = "size";

    /**
     * Query parameter name for sort field
     */
    public static final String PARAM_SORT = "sort";

    // ============= USER ROLES =============

    /**
     * Standard user role
     */
    public static final String ROLE_USER = "ROLE_USER";

    /**
     * Administrator role
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Premium user role (for stretch goals)
     */
    public static final String ROLE_PREMIUM = "ROLE_PREMIUM";

    // ============= VALIDATION CONSTRAINTS =============

    /**
     * Minimum password length
     */
    public static final int PASSWORD_MIN_LENGTH = 8;

    /**
     * Maximum password length
     */
    public static final int PASSWORD_MAX_LENGTH = 128;

    /**
     * Email maximum length
     */
    public static final int EMAIL_MAX_LENGTH = 255;

    /**
     * Username minimum length
     */
    public static final int USERNAME_MIN_LENGTH = 3;

    /**
     * Username maximum length
     */
    public static final int USERNAME_MAX_LENGTH = 50;

    // ============= COOKIE NAMES =============

    /**
     * Refresh token cookie name (for HttpOnly cookie storage)
     */
    public static final String COOKIE_REFRESH_TOKEN = "refresh_token";

    /**
     * Cookie max age for refresh token (30 days in seconds)
     */
    public static final int COOKIE_REFRESH_TOKEN_MAX_AGE = 30 * 24 * 60 * 60;

    // ============= RESPONSE MESSAGES =============

    /**
     * Generic success message
     */
    public static final String MSG_SUCCESS = "Operation completed successfully";

    /**
     * Login success message
     */
    public static final String MSG_LOGIN_SUCCESS = "Login successful";

    /**
     * Logout success message
     */
    public static final String MSG_LOGOUT_SUCCESS = "Logout successful";

    /**
     * Registration success message
     */
    public static final String MSG_REGISTER_SUCCESS = "Registration successful";

    /**
     * Token refresh success message
     */
    public static final String MSG_TOKEN_REFRESH_SUCCESS = "Token refreshed successfully";

    // ============= DATE & TIME FORMATS =============

    /**
     * ISO 8601 date-time format
     * <br>Example: 2025-01-15T10:30:00Z
     */
    public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    /**
     * Date format
     * <br>Example: 2025-01-15
     */
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    // ============= REGEX PATTERNS =============

    /**
     * Email validation regex (RFC 5322 simplified)
     */
    public static final String REGEX_EMAIL = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    /**
     * Password validation regex
     * <br>At least 8 characters, 1 uppercase, 1 lowercase, 1 number
     */
    public static final String REGEX_PASSWORD = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$";
}
