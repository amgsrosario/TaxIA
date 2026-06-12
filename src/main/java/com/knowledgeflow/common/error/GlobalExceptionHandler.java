package com.knowledgeflow.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return buildResponse(
                ApiErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST,
                details
        );
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiErrorResponse> handleValidation(Exception exception, HttpServletRequest request) {
        return buildResponse(
                ApiErrorCode.VALIDATION_ERROR,
                "Request validation failed",
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST,
                Map.of("error", exception.getMessage())
        );
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                exception.getCode(),
                exception.getMessage(),
                request.getRequestURI(),
                statusFor(exception.getCode()),
                Map.of()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                ApiErrorCode.NOT_FOUND,
                "Resource was not found",
                request.getRequestURI(),
                HttpStatus.NOT_FOUND,
                Map.of()
        );
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(Exception exception, HttpServletRequest request) {
        return buildResponse(
                ApiErrorCode.UNAUTHORIZED,
                "Authentication is required",
                request.getRequestURI(),
                HttpStatus.UNAUTHORIZED,
                Map.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(Exception exception, HttpServletRequest request) {
        return buildResponse(
                ApiErrorCode.FORBIDDEN,
                "Access denied",
                request.getRequestURI(),
                HttpStatus.FORBIDDEN,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        return buildResponse(
                ApiErrorCode.INTERNAL_ERROR,
                "Unexpected internal error",
                request.getRequestURI(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            ApiErrorCode code,
            String message,
            String path,
            HttpStatus status,
            Map<String, Object> details
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code.name(),
                message,
                path,
                OffsetDateTime.now(),
                details
        ));
    }

    private HttpStatus statusFor(ApiErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT, INVALID_STATE_TRANSITION -> HttpStatus.CONFLICT;
            case EXTERNAL_SERVICE_ERROR -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
