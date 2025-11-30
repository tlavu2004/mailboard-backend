package com.awad.emailclientai.shared.dto.response;

import com.awad.emailclientai.shared.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Generic API Response Wrapper
 *
 * <p>Standardized response format for all API endpoints.
 * <br>Provides consistent structure for success and error responses.
 *
 * <p><b>Success Response Example:</b>
 * <pre>
 * {
 *   "success": true,
 *   "message": "Login successful",
 *   "data": {
 *     "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *     "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *     "user": {
 *       "id": 1,
 *       "email": "user@example.com"
 *     }
 *   },
 *   "timestamp": "2025-01-15T10:30:00"
 * }
 * </pre>
 *
 * <p><b>Error Response Example:</b>
 * <pre>
 * {
 *   "success": false,
 *   "message": "Validation failed",
 *   "errorCode": "VALIDATION_001",
 *   "errors": [
 *     {
 *       "field": "email",
 *       "message": "Email must be valid"
 *     }
 *   ],
 *   "timestamp": "2025-01-15T10:30:00"
 * }
 * </pre>
 *
 * <p><b>Usage Examples:</b>
 * <pre>
 * // Success response with data
 * return ResponseEntity.ok(
 *     ApiResponse.success("User created", userDto)
 * );
 *
 * // Success response without data
 * return ResponseEntity.ok(
 *     ApiResponse.success("Operation completed")
 * );
 *
 * // Error response
 * return ResponseEntity.badRequest().body(
 *     ApiResponse.error(ErrorCode.VALIDATION_ERROR, validationErrors)
 * );
 *
 * // Error response without validation errors
 * return ResponseEntity.status(401).body(
 *     ApiResponse.error(ErrorCode.INVALID_CREDENTIALS)
 * );
 * </pre>
 *
 * @param <T> the type of data in the response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // Exclude null fields from JSON
public class ApiResponse<T> {

    /**
     * Indicates whether the request was successful
     *
     * <ul>
     *   <li><b>true:</b> Request processed successfully (2xx status codes)</li>
     *   <li><b>false:</b> Request failed (4xx, 5xx status codes)</li>
     * </ul>
     */
    private boolean success;

    /**
     * User-friendly message describing the result
     *
     * <p><b>Success examples:</b>
     * <ul>
     *   <li>"Login successful"</li>
     *   <li>"User created successfully"</li>
     *   <li>"Email sent"</li>
     * </ul>
     *
     * <p><b>Error examples:</b>
     * <ul>
     *   <li>"Invalid email or password"</li>
     *   <li>"Validation failed"</li>
     *   <li>"User not found"</li>
     * </ul>
     */
    private String message;

    /**
     * Response data payload (null for error responses)
     *
     * <p>Can be any type: DTO, List, Map, String, etc.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>UserDto - for user profile endpoint</li>
     *   <li>List&lt;EmailDto&gt; - for email list endpoint</li>
     *   <li>LoginResponseDto - for login endpoint</li>
     *   <li>null - for simple success/error responses</li>
     * </ul>
     */
    private T data;

    /**
     * Error code for failed requests (null for successful requests)
     *
     * <p>Allows frontend to implement specific error handling logic.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>"AUTH_001" - Invalid credentials</li>
     *   <li>"AUTH_002" - Token expired</li>
     *   <li>"VALIDATION_001" - Validation failed</li>
     * </ul>
     *
     * @see ErrorCode
     */
    private String errorCode;

    /**
     * List of validation errors (only for validation failures)
     *
     * <p>Provides detailed field-level validation feedback.
     * <br>Null or empty for non-validation errors.
     *
     * @see ValidationError
     */
    private List<ValidationError> errors;

    /**
     * Timestamp when the response was generated
     *
     * <p>Format: ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)
     * <br>Example: 2025-01-15T10:30:00
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // ============= STATIC FACTORY METHODS FOR SUCCESS =============

    /**
     * Create a success response with data and message
     *
     * @param message success message
     * @param data response data
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a success response with only message (no data)
     *
     * @param message success message
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a success response with data and default message
     *
     * @param data response data
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message("Operation completed successfully")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a simple success response with default message
     *
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .success(true)
                .message("Operation completed successfully")
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ============= STATIC FACTORY METHODS FOR ERRORS =============

    /**
     * Create an error response with ErrorCode and validation errors
     *
     * @param errorCode the error code
     * @param errors list of validation errors
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, List<ValidationError> errors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(errorCode.getMessage())
                .errorCode(errorCode.getCode())
                .errors(errors)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create an error response with ErrorCode only
     *
     * @param errorCode the error code
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(errorCode.getMessage())
                .errorCode(errorCode.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create an error response with ErrorCode and custom message
     *
     * @param errorCode the error code
     * @param customMessage custom error message (overrides default)
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(customMessage)
                .errorCode(errorCode.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create an error response with custom message only (no error code)
     *
     * @param message error message
     * @param <T> data type
     * @return ApiResponse instance
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
