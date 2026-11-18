package com.moneytransfer.shared.api;

import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.shared.filter.TraceIdFilter;
import com.moneytransfer.transfer.exception.IdempotencyConflictException;
import com.moneytransfer.transfer.exception.InvalidTransferQueryException;
import com.moneytransfer.transfer.exception.TransferNotFoundException;
import com.moneytransfer.transfer.exception.TransferRuleException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

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

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(code, message, TraceIdFilter.getTraceId(request)));
    }
}
