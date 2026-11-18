package com.moneytransfer.shared.api;

import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.shared.filter.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
