package com.moneytransfer.shared.api;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(Error error) {

    public static ApiErrorResponse of(String code, String message, String traceId) {
        return new ApiErrorResponse(new Error(code, message, traceId, null, null));
    }

    public static ApiErrorResponse of(String code, String message, String traceId, String path, List<String> details) {
        return new ApiErrorResponse(new Error(code, message, traceId, path, details));
    }

    public record Error(
            String code,
            String message,
            String traceId,
            String path,
            List<String> details) {
    }
}
