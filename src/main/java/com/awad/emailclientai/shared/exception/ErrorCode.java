package com.awad.emailclientai.shared.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Centralized Error Code Enumeration
 *
 * <p>Defines all error codes used across the application with consistent format:
 * <ul>
 *   <li><b>AUTH_XXX:</b> Authentication & Authorization errors (401, 403)</li>
 *   <li><b>VALIDATION_XXX:</b> Input validation errors (400)</li>
 *   <li><b>BUSINESS_XXX:</b> Business logic errors (400, 409, 422)</li>
 *   <li><b>RESOURCE_XXX:</b> Resource not found errors (404)</li>
 *   <li><b>SYSTEM_XXX:</b> Internal server errors (500)</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>
 * throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
 * throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED);
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ============= AUTHENTICATION & AUTHORIZATION (AUTH_XXX) =============

    /**
     * AUTH_001: Invalid email or password
     * Triggered when: User provides wrong credentials during login
     */
    INVALID_CREDENTIALS("AUTH_001", "Invalid email or password", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_002: Access token has expired
     * Triggered when: JWT access token is expired
     */
    TOKEN_EXPIRED("AUTH_002", "Your session has expired. Please login again", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_003: Invalid or malformed JWT token
     * Triggered when: JWT token signature invalid, format wrong, or tampered
     */
    INVALID_TOKEN("AUTH_003", "Invalid authentication token", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_004: Refresh token has expired or invalid
     * Triggered when: Attempting to refresh with expired/invalid refresh token
     */
    REFRESH_TOKEN_EXPIRED("AUTH_004", "Refresh token expired. Please login again", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_005: Google OAuth token validation failed
     * Triggered when: Google ID token is invalid or verification fails
     */
    INVALID_GOOGLE_TOKEN("AUTH_005", "Invalid Google authentication token", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_006: Authentication is required
     * Triggered when: User tries to access protected resource without authentication
     */
    AUTHENTICATION_REQUIRED("AUTH_006", "Authentication is required to access this resource", HttpStatus.UNAUTHORIZED),

    /**
     * AUTH_007: Insufficient permissions
     * Triggered when: User doesn't have required role/permission
     */
    ACCESS_DENIED("AUTH_007", "You don't have permission to access this resource", HttpStatus.FORBIDDEN),

    /**
     * AUTH_008: Account is disabled
     * Triggered when: User account has been deactivated
     */
    ACCOUNT_DISABLED("AUTH_008", "Your account has been disabled. Please contact support", HttpStatus.FORBIDDEN),

    // ============= VALIDATION ERRORS (VALIDATION_XXX) =============

    /**
     * VALIDATION_001: Generic validation error
     * Triggered when: Request body validation fails (e.g., @Valid, @NotNull, @Email)
     */
    VALIDATION_ERROR("VALIDATION_001", "Validation failed for one or more fields", HttpStatus.BAD_REQUEST),

    /**
     * VALIDATION_002: Invalid email format
     * Triggered when: Email doesn't match pattern
     */
    INVALID_EMAIL_FORMAT("VALIDATION_002", "Invalid email format", HttpStatus.BAD_REQUEST),

    /**
     * VALIDATION_003: Password doesn't meet requirements
     * Triggered when: Password too weak (length, complexity)
     */
    INVALID_PASSWORD_FORMAT("VALIDATION_003", "Password must be at least 8 characters and contain uppercase, lowercase, and number", HttpStatus.BAD_REQUEST),

    /**
     * VALIDATION_004: Required field is missing
     * Triggered when: Mandatory field is null or empty
     */
    REQUIRED_FIELD_MISSING("VALIDATION_004", "Required field is missing", HttpStatus.BAD_REQUEST),

    // ============= BUSINESS LOGIC ERRORS (BUSINESS_XXX) =============

    /**
     * BUSINESS_001: User with email already exists
     * Triggered when: Attempting to register with existing email
     */
    USER_ALREADY_EXISTS("BUSINESS_001", "An account with this email already exists", HttpStatus.CONFLICT),

    /**
     * BUSINESS_002: Email not verified
     * Triggered when: User tries to login without verifying email (if required)
     */
    EMAIL_NOT_VERIFIED("BUSINESS_002", "Please verify your email before logging in", HttpStatus.FORBIDDEN),

    /**
     * BUSINESS_003: Password reset token invalid or expired
     * Triggered when: Using expired/invalid password reset token
     */
    INVALID_RESET_TOKEN("BUSINESS_003", "Password reset link is invalid or expired", HttpStatus.BAD_REQUEST),

    /**
     * BUSINESS_004: Cannot perform operation
     * Triggered when: Business rule violation (e.g., cannot delete own account)
     */
    OPERATION_NOT_ALLOWED("BUSINESS_004", "This operation is not allowed", HttpStatus.UNPROCESSABLE_ENTITY),

    // ============= RESOURCE ERRORS (RESOURCE_XXX) =============

    /**
     * RESOURCE_001: User not found
     * Triggered when: Querying user by ID/email that doesn't exist
     */
    USER_NOT_FOUND("RESOURCE_001", "User not found", HttpStatus.NOT_FOUND),

    /**
     * RESOURCE_002: Mailbox not found
     * Triggered when: Accessing non-existent mailbox
     */
    MAILBOX_NOT_FOUND("RESOURCE_002", "Mailbox not found", HttpStatus.NOT_FOUND),

    /**
     * RESOURCE_003: Email not found
     * Triggered when: Accessing non-existent email
     */
    EMAIL_NOT_FOUND("RESOURCE_003", "Email not found", HttpStatus.NOT_FOUND),

    /**
     * RESOURCE_004: Requested resource not found
     * Triggered when: Generic 404 for any resource
     */
    RESOURCE_NOT_FOUND("RESOURCE_004", "Requested resource not found", HttpStatus.NOT_FOUND),

    // ============= SYSTEM ERRORS (SYSTEM_XXX) =============

    /**
     * SYSTEM_001: Internal server error
     * Triggered when: Unexpected exception occurs
     */
    INTERNAL_ERROR("SYSTEM_001", "An unexpected error occurred. Please try again later", HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * SYSTEM_002: Database error
     * Triggered when: Database connection/query fails
     */
    DATABASE_ERROR("SYSTEM_002", "Database error occurred", HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * SYSTEM_003: External service error
     * Triggered when: Third-party API call fails (Google OAuth, etc.)
     */
    EXTERNAL_SERVICE_ERROR("SYSTEM_003", "External service is unavailable", HttpStatus.SERVICE_UNAVAILABLE),

    /**
     * SYSTEM_004: File operation error
     * Triggered when: File upload/download fails
     */
    FILE_OPERATION_ERROR("SYSTEM_004", "File operation failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // ============= EMAIL ACCOUNT ERRORS (EMAIL_XXX) =============

    /**
     * EMAIL_001: Email account already linked
     * Triggered when: User tries to link an already connected email
     */
    EMAIL_ACCOUNT_ALREADY_EXISTS("EMAIL_001", "This email account is already linked", HttpStatus.CONFLICT),

    /**
     * EMAIL_002: Email account not found
     * Triggered when: Accessing non-existent email account
     */
    EMAIL_ACCOUNT_NOT_FOUND("EMAIL_002", "Email account not found", HttpStatus.NOT_FOUND),

    /**
     * EMAIL_003: IMAP connection failed
     * Triggered when: Cannot connect to IMAP server
     */
    IMAP_CONNECTION_FAILED("EMAIL_003", "Failed to connect to email server. Please check your credentials", HttpStatus.BAD_REQUEST),

    /**
     * EMAIL_004: SMTP connection failed
     * Triggered when: Cannot connect to SMTP server
     */
    SMTP_CONNECTION_FAILED("EMAIL_004", "Failed to connect to mail sending server", HttpStatus.BAD_REQUEST),

    /**
     * EMAIL_005: Email folder not found
     * Triggered when: Accessing non-existent email folder
     */
    EMAIL_FOLDER_NOT_FOUND("EMAIL_005", "Email folder not found", HttpStatus.NOT_FOUND),

    /**
     * EMAIL_006: Email message not found
     * Triggered when: Accessing non-existent email message
     */
    EMAIL_MESSAGE_NOT_FOUND("EMAIL_006", "Email message not found", HttpStatus.NOT_FOUND),

    /**
     * EMAIL_007: Email send failed
     * Triggered when: Failed to send email via SMTP
     */
    EMAIL_SEND_FAILED("EMAIL_007", "Failed to send email", HttpStatus.INTERNAL_SERVER_ERROR),

    /**
     * EMAIL_008: Attachment not found
     * Triggered when: Accessing non-existent attachment
     */
    EMAIL_ATTACHMENT_NOT_FOUND("EMAIL_008", "Attachment not found", HttpStatus.NOT_FOUND),

    /**
     * EMAIL_009: Social login users cannot link additional accounts
     * Triggered when: Google/Microsoft user tries to connect another email
     */
    EMAIL_LINKING_DISABLED_FOR_SOCIAL("EMAIL_009", "Account linking is not available for social login users. Please use local registration for multi-account support.", HttpStatus.FORBIDDEN),

    // ============= KANBAN ERRORS (KANBAN_XXX) =============

    /**
     * KANBAN_001: Kanban column not found
     * Triggered when: Accessing non-existent kanban column
     */
    KANBAN_COLUMN_NOT_FOUND("KANBAN_001", "Kanban column not found", HttpStatus.NOT_FOUND),

    /**
     * KANBAN_002: Cannot modify default column
     * Triggered when: User tries to rename a default column
     */
    KANBAN_CANNOT_MODIFY_DEFAULT("KANBAN_002", "Cannot rename a default column", HttpStatus.FORBIDDEN),

    /**
     * KANBAN_003: Cannot delete default column
     * Triggered when: User tries to delete a default column
     */
    KANBAN_CANNOT_DELETE_DEFAULT("KANBAN_003", "Cannot delete a default column", HttpStatus.FORBIDDEN);

    // ============= ENUM FIELDS =============

    /**
     * Unique error code identifier (e.g., "AUTH_001")
     */
    private final String code;

    /**
     * User-friendly error message
     */
    private final String message;

    /**
     * HTTP status code to return
     */
    private final HttpStatus httpStatus;

    /**
     * Get HTTP status code value (e.g., 401, 404, 500)
     *
     * @return HTTP status code as integer
     */
    public int getStatusCode() {
        return httpStatus.value();
    }
}
