package com.moneytransfer.shared.api;

import java.util.Map;

public record ApiResponse<T>(T data, Map<String, Object> meta) {

    public ApiResponse(T data) {
        this(data, Map.of());
    }
}
