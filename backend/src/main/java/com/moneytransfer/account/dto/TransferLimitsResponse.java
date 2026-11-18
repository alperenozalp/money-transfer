package com.moneytransfer.account.dto;

public record TransferLimitsResponse(
        String currency,
        String perTransferMaximum,
        String dailyRemaining,
        String monthlyRemaining) {
}
