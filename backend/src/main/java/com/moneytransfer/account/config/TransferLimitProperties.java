package com.moneytransfer.account.config;

import java.math.BigDecimal;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "transfer")
public record TransferLimitProperties(
        @NotNull ZoneId businessTimeZone,
        @Valid @NotNull Limits limits) {

    public record Limits(
            @NotNull @Positive BigDecimal perTransfer,
            @NotNull @Positive BigDecimal daily,
            @NotNull @Positive BigDecimal monthly) {
    }
}
