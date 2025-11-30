package com.awad.emailclientai.shared.exception;

import lombok.Getter;

/**
 * Business Logic Exception
 *
 * <p>Custom exception for handling business rule violations and domain-specific errors.
 * <br>This exception should be thrown when business logic constraints are violated,
 * such as duplicate entries, invalid operations, or business rule failures.
 *
 * <p><b>When to use:</b>
 * <ul>
 *   <li>User tries to register with an existing email</li>
 *   <li>User tries to perform an operation they're not allowed to</li>
 *   <li>Business rule validation fails (e.g., cannot delete own account)</li>
 *   <li>Resource state doesn't allow the requested operation</li>
 * </ul>
 *
 * <p><b>When NOT to use:</b>
 * <ul>
 *   <li>Authentication/Authorization failures → use {@link UnauthorizedException}</li>
 *   <li>Input validation failures → handled by Spring's @Valid</li>
 *   <li>Resource not found → use Spring's built-in exceptions or custom NotFoundException</li>
 *   <li>Internal server errors → use standard RuntimeException or specific technical exceptions</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b>
 * <pre>
 * // Example 1: User already exists
 * if (userRepository.existsByEmail(email)) {
 *     throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
 * }
 *
 * // Example 2: Custom message with error code
 * throw new BusinessException(
 *     ErrorCode.OPERATION_NOT_ALLOWED,
 *     "Cannot delete account while active subscriptions exist"
 * );
 *
 * // Example 3: With cause (wrapping another exception)
 * try {
 *     externalService.performOperation();
 * } catch (ExternalServiceException e) {
 *     throw new BusinessException(
 *         ErrorCode.EXTERNAL_SERVICE_ERROR,
 *         "Failed to sync with external service",
 *         e
 *     );
 * }
 * </pre>
 *
 * <p><b>Handling:</b>
 * <br>This exception is automatically caught by {@link GlobalExceptionHandler}
 * and converted to a standardized {@link com.awad.emailclientai.shared.dto.response.ApiResponse} format.
 *
 * @see ErrorCode
 * @see GlobalExceptionHandler
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * The error code associated with this exception
     * <br>Used for consistent error identification across the application
     */
    private final ErrorCode errorCode;

    /**
     * Additional context or details about the error (optional)
     * <br>Can be used to provide dynamic information not in the error code
     */
    private final String details;

    /**
     * Creates a business exception with an error code
     * <br>Uses the error code's default message
     *
     * @param errorCode the error code
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates a business exception with an error code and custom message
     * <br>Custom message overrides the error code's default message
     *
     * @param errorCode the error code
     * @param message custom error message
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates a business exception with an error code and additional details
     * <br>Details provide extra context while keeping the default message
     *
     * @param errorCode the error code
     * @param message custom error message
     * @param details additional error details
     */
    public BusinessException(ErrorCode errorCode, String message, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * Creates a business exception with an error code and root cause
     * <br>Useful for wrapping lower-level exceptions
     *
     * @param errorCode the error code
     * @param message custom error message
     * @param cause the underlying cause
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates a business exception with all parameters
     * <br>Most flexible constructor for complex error scenarios
     *
     * @param errorCode the error code
     * @param message custom error message
     * @param details additional error details
     * @param cause the underlying cause
     */
    public BusinessException(ErrorCode errorCode, String message, String details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * Gets the HTTP status code associated with this error
     *
     * @return HTTP status code (e.g., 400, 409, 422)
     */
    public int getStatusCode() {
        return errorCode.getStatusCode();
    }

    /**
     * Gets the error code as a string
     *
     * @return error code (e.g., "BUSINESS_001")
     */
    public String getCode() {
        return errorCode.getCode();
    }

    @Override
    public String toString() {
        return String.format(
                "BusinessException[code=%s, message=%s, details=%s]",
                errorCode.getCode(),
                getMessage(),
                details
        );
    }
}
