package com.moneytransfer.statistics.dto;

public record MonthlyTransferStatisticsResponse(
        String month,
        long completedTransferCount,
        String completedOutgoingAmount) {
}
