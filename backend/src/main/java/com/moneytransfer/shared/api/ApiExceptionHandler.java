package com.moneytransfer.shared.api;

import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.security.local.InvalidDemoCredentialsException;
import com.moneytransfer.shared.filter.TraceIdFilter;
import com.moneytransfer.transfer.exception.IdempotencyConflictException;
import com.moneytransfer.transfer.exception.InvalidTransferQueryException;
import com.moneytransfer.transfer.exception.TransferNotFoundException;
import com.moneytransfer.transfer.exception.TransferRuleException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTransferNotFound(
            TransferNotFoundException exception,
            HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", exception.getMessage(), request);
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
        String message = exception instanceof InvalidTransferQueryException
                ? exception.getMessage()
                : "The request is invalid.";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
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

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        if (status.is5xxServerError()) {
            LOGGER.error("Request failed with an HTTP server error.", exception);
            return error(status, "INTERNAL_SERVER_ERROR", "An unexpected error occurred.", request);
        }
        return error(status, "REQUEST_REJECTED", "The request could not be completed.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        LOGGER.error("Unhandled API exception.", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred.",
                request);
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
