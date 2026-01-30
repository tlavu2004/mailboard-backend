package com.awad.emailclientai.shared.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Validation Error Data Transfer Object
 *
 * <p>Represents a single field validation error in the API response.
 * <br>Used by {@link ApiResponse} to provide detailed feedback when form validation fails.
 *
 * <p><b>Example JSON response:</b>
 * <pre>
 * {
 *   "success": false,
 *   "message": "Validation failed",
 *   "data": null,
 *   "errors": [
 *     {
 *       "field": "email",
 *       "message": "Email must be a valid email address",
 *       "rejectedValue": "invalid-email"
 *     },
 *     {
 *       "field": "password",
 *       "message": "Password must be at least 8 characters",
 *       "rejectedValue": "123"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p><b>Usage in GlobalExceptionHandler:</b>
 * <pre>
 * {@literal @}ExceptionHandler(MethodArgumentNotValidException.class)
 * public ResponseEntity{@literal <}ApiResponse{@literal <}Void{@literal >}{@literal >} handleValidationException(
 *     MethodArgumentNotValidException ex
 * ) {
 *     List{@literal <}ValidationError{@literal >} errors = ex.getBindingResult()
 *         .getFieldErrors()
 *         .stream()
 *         .map(error {@literal ->} ValidationError.builder()
 *             .field(error.getField())
 *             .message(error.getDefaultMessage())
 *             .rejectedValue(error.getRejectedValue())
 *             .build())
 *         .toList();
 *
 *     return ResponseEntity.badRequest()
 *         .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, errors));
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // Exclude null fields from JSON
public class ValidationError {

    /**
     * The name of the field that failed validation
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"email" - for email field validation</li>
     *   <li>"password" - for password field validation</li>
     *   <li>"user.firstName" - for nested object validation</li>
     *   <li>"addresses[0].city" - for list element validation</li>
     * </ul>
     */
    private String field;

    /**
     * User-friendly error message describing why validation failed
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"Email must be a valid email address"</li>
     *   <li>"Password must be at least 8 characters"</li>
     *   <li>"First name is required"</li>
     *   <li>"Age must be between 18 and 120"</li>
     * </ul>
     *
     * <p><b>Best practices:</b>
     * <ul>
     *   <li>Use clear, concise language</li>
     *   <li>Specify what's expected (e.g., "at least 8 characters" not "too short")</li>
     *   <li>Avoid technical jargon</li>
     *   <li>Be specific about constraints</li>
     * </ul>
     */
    private String message;

    /**
     * The value that was rejected during validation (optional)
     *
     * <p>Useful for debugging and providing context to the user.
     * <br><b>Note:</b> Sensitive values (passwords, tokens) should NOT be included.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"invalid-email" - for invalid email format</li>
     *   <li>"12" - for age below minimum</li>
     *   <li>null - for missing required field</li>
     * </ul>
     *
     * <p><b>Security considerations:</b>
     * <ul>
     *   <li>Never include passwords</li>
     *   <li>Never include tokens or secrets</li>
     *   <li>Never include credit card numbers</li>
     *   <li>Consider privacy when including PII</li>
     * </ul>
     */
    private Object rejectedValue;

    /**
     * Error code for this specific validation error (optional)
     *
     * <p>Allows frontend to implement custom logic based on error codes.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"VALIDATION_EMAIL_FORMAT" - for email format validation</li>
     *   <li>"VALIDATION_PASSWORD_LENGTH" - for password length validation</li>
     *   <li>"VALIDATION_REQUIRED" - for required field validation</li>
     * </ul>
     */
    private String code;

    /**
     * Creates a simple validation error with field name and message only
     *
     * @param field the field name
     * @param message the error message
     * @return ValidationError instance
     */
    public static ValidationError of(String field, String message) {
        return ValidationError.builder()
                .field(field)
                .message(message)
                .build();
    }

    /**
     * Creates a validation error with field, message, and rejected value
     *
     * @param field the field name
     * @param message the error message
     * @param rejectedValue the value that was rejected
     * @return ValidationError instance
     */
    public static ValidationError of(String field, String message, Object rejectedValue) {
        return ValidationError.builder()
                .field(field)
                .message(message)
                .rejectedValue(rejectedValue)
                .build();
    }

    /**
     * Creates a validation error with all fields
     *
     * @param field the field name
     * @param message the error message
     * @param rejectedValue the value that was rejected
     * @param code the error code
     * @return ValidationError instance
     */
    public static ValidationError of(String field, String message, Object rejectedValue, String code) {
        return ValidationError.builder()
                .field(field)
                .message(message)
                .rejectedValue(rejectedValue)
                .code(code)
                .build();
    }
}
