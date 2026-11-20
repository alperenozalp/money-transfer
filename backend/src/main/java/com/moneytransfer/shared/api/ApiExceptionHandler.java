package com.moneytransfer.shared.api;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.security.local.InvalidDemoCredentialsException;
import com.moneytransfer.shared.filter.TraceIdFilter;
import com.moneytransfer.transfer.exception.IdempotencyConflictException;
import com.moneytransfer.transfer.exception.InvalidTransferQueryException;
import com.moneytransfer.transfer.exception.TransferNotFoundException;
import com.moneytransfer.transfer.exception.TransferRuleException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ObjectMapper objectMapper;

    public ApiExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(InvalidDemoCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDemoCredentials(
            InvalidDemoCredentialsException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid customer ID or password.",
                request);
    }

    @ExceptionHandler(CustomerNotProvisionedException.class)
    public ResponseEntity<ApiErrorResponse> handleCustomerNotProvisioned(
            CustomerNotProvisionedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(
                        "CUSTOMER_NOT_PROVISIONED",
                        "The authenticated customer is not enabled for this service.",
                        TraceIdFilter.getTraceId(request)));
    }

    @ExceptionHandler(TransferRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleTransferRule(
            TransferRuleException exception,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.getTraceId(request);
        LOGGER.warn("Transfer rule violation [traceId={}]: code={}, message={}, uri={}",
                traceId, exception.getCode(), exception.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.of(
                        exception.getCode(),
                        exception.getMessage(),
                        traceId,
                        request.getRequestURI(),
                        null));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.getTraceId(request);
        LOGGER.warn("Idempotency conflict [traceId={}]: uri={}", traceId, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(
                        "IDEMPOTENCY_KEY_REUSED",
                        exception.getMessage(),
                        traceId,
                        request.getRequestURI(),
                        null));
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTransferNotFound(
            TransferNotFoundException exception,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.getTraceId(request);
        LOGGER.info("Transfer not found [traceId={}]: uri={}", traceId, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(
                        "TRANSFER_NOT_FOUND",
                        exception.getMessage(),
                        traceId,
                        request.getRequestURI(),
                        null));
    }

    @ExceptionHandler({
            InvalidTransferQueryException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request) {
        String traceId = TraceIdFilter.getTraceId(request);
        java.util.List<String> details = null;
        String message = "The request is invalid.";

        if (exception instanceof MethodArgumentNotValidException validationException) {
            details = validationException.getBindingResult().getFieldErrors().stream()
                    .map(error -> "'" + error.getField() + "': " + error.getDefaultMessage())
                    .toList();
            message = "Validation failed: " + String.join(", ", details);
        } else if (exception instanceof InvalidTransferQueryException queryException) {
            message = queryException.getMessage();
        } else if (exception instanceof MissingRequestHeaderException headerException) {
            message = "Required header is missing: " + headerException.getHeaderName();
        }

        LOGGER.warn("Invalid request [traceId={}]: message={}, uri={}", traceId, message, request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_REQUEST", message, traceId, request.getRequestURI(), details));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "The HTTP method is not supported for this endpoint.",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "The request content type is not supported.",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_ACCEPTABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(
                        "NOT_ACCEPTABLE",
                        "The requested response format is not supported.",
                        TraceIdFilter.getTraceId(request)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        return handleFrameworkError(exception, exception.getStatusCode(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
                return handleFrameworkError(exception, errorResponse.getStatusCode(), request);
        }
        LOGGER.error("Unhandled API exception.", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred.",
                request);
    }

    private ResponseEntity<ApiErrorResponse> handleFrameworkError(
            Exception exception,
            HttpStatusCode status,
            HttpServletRequest request) {
        if (status.is5xxServerError()) {
            LOGGER.error("Request failed with an HTTP server error. Status: {}", status.value(), exception);
            return error(status, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.", request);
        }
        return switch (status.value()) {
            case 400 -> error(status, "INVALID_REQUEST", "The request is invalid.", request);
            case 401 -> error(status, "UNAUTHENTICATED", "Authentication is required.", request);
            case 403 -> error(
                    status,
                    "FORBIDDEN",
                    "You are not allowed to access this resource.",
                    request);
            case 404 -> error(status, "NOT_FOUND", "The requested resource was not found.", request);
            case 405 -> error(
                    status,
                    "METHOD_NOT_ALLOWED",
                    "The HTTP method is not supported for this endpoint.",
                    request);
            case 406 -> error(
                    status,
                    "NOT_ACCEPTABLE",
                    "The requested response format is not supported.",
                    request);
            case 408 -> error(status, "REQUEST_TIMEOUT", "The request timed out.", request);
            case 409 -> error(
                    status,
                    "CONFLICT",
                    "The request conflicts with the current resource state.",
                    request);
            case 413 -> error(status, "PAYLOAD_TOO_LARGE", "The request payload is too large.", request);
            case 415 -> error(
                    status,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "The request content type is not supported.",
                    request);
            case 422 -> error(status, "UNPROCESSABLE_ENTITY", "The request could not be processed.", request);
            case 429 -> error(status, "TOO_MANY_REQUESTS", "Too many requests.", request);
            default -> error(status, "REQUEST_REJECTED", "The request could not be completed.", request);
        };
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatusCode status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(code, message, TraceIdFilter.getTraceId(request)));
    }
}
