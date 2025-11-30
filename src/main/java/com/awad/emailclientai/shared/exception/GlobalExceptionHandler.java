package com.awad.emailclientai.shared.exception;

import com.awad.emailclientai.shared.dto.response.ApiResponse;
import com.awad.emailclientai.shared.dto.response.ValidationError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global Exception Handler
 *
 * <p>Centralized exception handling for the entire application using {@code @RestControllerAdvice}.
 * <br>Catches all exceptions thrown by controllers and converts them to standardized
 * {@link ApiResponse} format with appropriate HTTP status codes.
 *
 * <p><b>Exception Handling Priority:</b>
 * <ol>
 *   <li>Custom exceptions (BusinessException, UnauthorizedException)</li>
 *   <li>Spring validation exceptions (MethodArgumentNotValidException)</li>
 *   <li>Spring security exceptions (AuthenticationException, AccessDeniedException)</li>
 *   <li>Common Spring exceptions (404, 400, etc.)</li>
 *   <li>Generic exceptions (catch-all)</li>
 * </ol>
 *
 * <p><b>Logging Strategy:</b>
 * <ul>
 *   <li><b>WARN:</b> Client errors (4xx) - expected errors like validation failures</li>
 *   <li><b>ERROR:</b> Server errors (5xx) - unexpected errors requiring investigation</li>
 *   <li><b>DEBUG:</b> Detailed exception traces for development</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ============= CUSTOM APPLICATION EXCEPTIONS =============

    /**
     * Handles BusinessException
     * <br>Thrown when business logic rules are violated
     *
     * <p><b>HTTP Status:</b> Varies based on ErrorCode (typically 400, 409, 422)
     *
     * @param ex the business exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex,
            HttpServletRequest request
    ) {
        log.warn("Business exception: {} - Path: {} - Code: {}",
                ex.getMessage(), request.getRequestURI(), ex.getCode());

        ApiResponse<Void> response = ApiResponse.error(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity
                .status(ex.getStatusCode())
                .body(response);
    }

    /**
     * Handles UnauthorizedException
     * <br>Thrown when authentication or authorization fails
     *
     * <p><b>HTTP Status:</b> 401 (Unauthorized) or 403 (Forbidden)
     *
     * @param ex the unauthorized exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(
            UnauthorizedException ex,
            HttpServletRequest request
    ) {
        log.warn("Unauthorized access: {} - Path: {} - Code: {}",
                ex.getMessage(), request.getRequestURI(), ex.getCode());

        ApiResponse<Void> response = ApiResponse.error(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity
                .status(ex.getStatusCode())
                .body(response);
    }

    // ============= VALIDATION EXCEPTIONS =============

    /**
     * Handles MethodArgumentNotValidException
     * <br>Thrown when {@code @Valid} or {@code @Validated} annotation validation fails
     *
     * <p><b>HTTP Status:</b> 400 (Bad Request)
     *
     * <p><b>Example:</b>
     * <pre>
     * {
     *   "success": false,
     *   "message": "Validation failed",
     *   "errorCode": "VALIDATION_001",
     *   "errors": [
     *     {"field": "email", "message": "Email must be valid"},
     *     {"field": "password", "message": "Password is required"}
     *   ]
     * }
     * </pre>
     *
     * @param ex the validation exception
     * @param request the HTTP request
     * @return standardized error response with validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ValidationError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::mapFieldError)
                .collect(Collectors.toList());

        log.warn("Validation failed: {} errors - Path: {}",
                errors.size(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.VALIDATION_ERROR,
                errors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Maps Spring's FieldError to our ValidationError DTO
     *
     * @param fieldError the field error from Spring
     * @return ValidationError DTO
     */
    private ValidationError mapFieldError(FieldError fieldError) {
        return ValidationError.builder()
                .field(fieldError.getField())
                .message(fieldError.getDefaultMessage())
                .rejectedValue(fieldError.getRejectedValue())
                .build();
    }

    /**
     * Handles HttpMessageNotReadableException
     * <br>Thrown when request body is malformed or cannot be parsed
     *
     * <p><b>HTTP Status:</b> 400 (Bad Request)
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        log.warn("Malformed request body - Path: {} - Error: {}",
                request.getRequestURI(), ex.getMessage());

        ApiResponse<Void> response = ApiResponse.error(
                "Invalid request body format. Please check your JSON syntax."
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handles MissingServletRequestParameterException
     * <br>Thrown when required request parameter is missing
     *
     * <p><b>HTTP Status:</b> 400 (Bad Request)
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request
    ) {
        log.warn("Missing required parameter: {} - Path: {}",
                ex.getParameterName(), request.getRequestURI());

        String message = String.format("Required parameter '%s' is missing",
                ex.getParameterName());

        ApiResponse<Void> response = ApiResponse.error(message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handles MethodArgumentTypeMismatchException
     * <br>Thrown when request parameter type conversion fails
     *
     * <p><b>HTTP Status:</b> 400 (Bad Request)
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        log.warn("Type mismatch for parameter: {} - Path: {}",
                ex.getName(), request.getRequestURI());

        String message = String.format(
                "Invalid value '%s' for parameter '%s'. Expected type: %s",
                ex.getValue(),
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown"
        );

        ApiResponse<Void> response = ApiResponse.error(message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    // ============= SPRING SECURITY EXCEPTIONS =============

    /**
     * Handles AuthenticationException
     * <br>Thrown by Spring Security when authentication fails
     *
     * <p><b>HTTP Status:</b> 401 (Unauthorized)
     *
     * @param ex the authentication exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication failed: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.AUTHENTICATION_REQUIRED
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }

    /**
     * Handles AccessDeniedException
     * <br>Thrown by Spring Security when user lacks required permissions
     *
     * <p><b>HTTP Status:</b> 403 (Forbidden)
     *
     * @param ex the access denied exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Access denied: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.FORBIDDEN
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    // ============= RESOURCE NOT FOUND =============

    /**
     * Handles NoHandlerFoundException
     * <br>Thrown when no handler is found for the requested URL (404)
     *
     * <p><b>HTTP Status:</b> 404 (Not Found)
     *
     * <p><b>Note:</b> Requires spring.mvc.throw-exception-if-no-handler-found=true
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(
            NoHandlerFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("No handler found for: {} {} - Path: {}",
                ex.getHttpMethod(), ex.getRequestURL(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.RESOURCE_NOT_FOUND
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    // ============= GENERIC EXCEPTION HANDLER (CATCH-ALL) =============

    /**
     * Handles all other unhandled exceptions
     * <br>Catch-all handler for unexpected errors
     *
     * <p><b>HTTP Status:</b> 500 (Internal Server Error)
     *
     * <p><b>Important:</b> This handler logs the full stack trace for debugging.
     * In production, avoid exposing internal error details to clients.
     *
     * @param ex the exception
     * @param request the HTTP request
     * @return standardized error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unexpected error occurred - Path: {} - Error: {}",
                request.getRequestURI(), ex.getMessage(), ex);

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.INTERNAL_SERVER_ERROR
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
