package com.knowledgeflow.common.error;

import com.knowledgeflow.ai.exception.AIConfigurationException;
import com.knowledgeflow.ai.exception.AIInvalidResponseException;
import com.knowledgeflow.ai.exception.AIProviderException;
import com.knowledgeflow.ai.exception.AIRateLimitException;
import com.knowledgeflow.ai.exception.AITimeoutException;
import com.knowledgeflow.ai.exception.AIUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(AIRateLimitException.class)
    public ResponseEntity<ApiErrorResponse> handleAIRateLimit(
            AIRateLimitException exception,
            HttpServletRequest request
    ) {
        log.warn("AI rate limit exceeded — {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        return buildResponse(
                ApiErrorCode.AI_RATE_LIMIT_ERROR,
                "AI provider rate limit exceeded — please retry later",
                request.getRequestURI(),
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of()
        );
    }

    @ExceptionHandler({AIConfigurationException.class})
    public ResponseEntity<ApiErrorResponse> handleAIConfiguration(
            AIConfigurationException exception,
            HttpServletRequest request
    ) {
        log.error("AI configuration error — {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage(), exception);
        return buildResponse(
                ApiErrorCode.AI_CONFIGURATION_ERROR,
                "AI service is not available due to a configuration error",
                request.getRequestURI(),
                HttpStatus.SERVICE_UNAVAILABLE,
                Map.of()
        );
    }

    @ExceptionHandler(AIUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAIUnavailable(
            AIUnavailableException exception,
            HttpServletRequest request
    ) {
        log.error("AI service unavailable — {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage(), exception);
        return buildResponse(
                ApiErrorCode.AI_PROVIDER_UNAVAILABLE,
                "AI service is temporarily unavailable",
                request.getRequestURI(),
                HttpStatus.SERVICE_UNAVAILABLE,
                Map.of()
        );
    }

    @ExceptionHandler(AITimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleAITimeout(
            AITimeoutException exception,
            HttpServletRequest request
    ) {
        log.error("AI provider timeout — {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage());
        return buildResponse(
                ApiErrorCode.AI_PROVIDER_TIMEOUT,
                "AI provider did not answer within the configured timeout",
                request.getRequestURI(),
                HttpStatus.GATEWAY_TIMEOUT,
                Map.of()
        );
    }

    @ExceptionHandler(AIInvalidResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleAIInvalidResponse(
            AIInvalidResponseException exception,
            HttpServletRequest request
    ) {
        log.error("AI provider returned an invalid response — {} {}: {}", request.getMethod(),
                request.getRequestURI(), exception.getMessage());
        return buildResponse(
                ApiErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                "AI provider returned an invalid or malformed response",
                request.getRequestURI(),
                HttpStatus.BAD_GATEWAY,
                Map.of()
        );
    }

    @ExceptionHandler(AIProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleAIProvider(
            AIProviderException exception,
            HttpServletRequest request
    ) {
        log.error("AI provider error — {} {}: {}", request.getMethod(), request.getRequestURI(),
                exception.getMessage(), exception);
        return buildResponse(
                ApiErrorCode.AI_PROVIDER_ERROR,
                "AI provider request failed",
                request.getRequestURI(),
                HttpStatus.BAD_GATEWAY,
                Map.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        log.warn("Unreadable request body — {} {}", request.getMethod(), request.getRequestURI());
        return buildResponse(
                ApiErrorCode.INVALID_REQUEST_BODY,
                "Request body is not valid JSON or is not encoded as UTF-8",
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST,
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
        log.error("Unhandled exception — {} {} — type: {}", request.getMethod(), request.getRequestURI(),
                exception.getClass().getName(), exception);
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
            case AI_PROVIDER_ERROR -> HttpStatus.BAD_GATEWAY;
            case AI_PROVIDER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_PROVIDER_INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case AI_RATE_LIMIT_ERROR -> HttpStatus.TOO_MANY_REQUESTS;
            case AI_CONFIGURATION_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
            case EMBEDDING_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case EMBEDDING_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case EMBEDDING_INVALID_VECTOR -> HttpStatus.BAD_GATEWAY;
            case INVALID_REQUEST_BODY -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
