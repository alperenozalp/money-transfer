package com.moneytransfer.shared.api;

public record ApiErrorResponse(Error error) {

    public static ApiErrorResponse of(String code, String message, String traceId) {
        return new ApiErrorResponse(new Error(code, message, traceId));
    }

    public record Error(String code, String message, String traceId) {
    }
}
