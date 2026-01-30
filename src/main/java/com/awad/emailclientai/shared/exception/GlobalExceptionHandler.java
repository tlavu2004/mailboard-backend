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

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(
            UnauthorizedException ex,
            HttpServletRequest request
    ) {
        log.warn("Unauthorized access: {} - Path: {} - Code: {}",
                ex.getMessage(), request.getRequestURI(), ex.getCode());

        // Assuming UnauthorizedException has getErrorCode() or we default to a specific one
        // If ex.getErrorCode() is not available in UnauthorizedException, use ErrorCode.ACCESS_DENIED or similar
        // Based on previous code, ex.getErrorCode() seemed available.
        ApiResponse<Void> response = ApiResponse.error(
                 ex.getErrorCode(),
                 ex.getMessage()
        );

        return ResponseEntity
                .status(ex.getStatusCode())
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<ValidationError>>> handleValidationException(
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

        ApiResponse<List<ValidationError>> response = ApiResponse.error(
                ErrorCode.VALIDATION_ERROR,
                errors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    private ValidationError mapFieldError(FieldError fieldError) {
        return ValidationError.builder()
                .field(fieldError.getField())
                .message(fieldError.getDefaultMessage())
                .rejectedValue(fieldError.getRejectedValue())
                .build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        log.warn("Malformed request body - Constants: {} - Error: {}",
                request.getRequestURI(), ex.getMessage());

        ApiResponse<Void> response = ApiResponse.error(
                "Invalid request body format: " + ex.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request
    ) {
        String message = String.format("Required parameter '%s' is missing",
                ex.getParameterName());

        ApiResponse<Void> response = ApiResponse.error(message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        String message = String.format(
                "Invalid value '%s' for parameter '%s'",
                ex.getValue(),
                ex.getName()
        );

        ApiResponse<Void> response = ApiResponse.error(message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication failed: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.AUTHENTICATION_REQUIRED,
                ex.getMessage()
        );

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Access denied: {} - Path: {}",
                ex.getMessage(), request.getRequestURI());

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.ACCESS_DENIED,
                "Access denied"
        );

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(response);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(
            NoHandlerFoundException ex,
            HttpServletRequest request
    ) {
        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.RESOURCE_NOT_FOUND,
                "Resource not found"
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unexpected error occurred - Path: {} - Error: {}",
                request.getRequestURI(), ex.getMessage(), ex);

        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.INTERNAL_ERROR
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
