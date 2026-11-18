package com.moneytransfer.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.moneytransfer.transfer.model.TransferStatus;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        String receiverIban,
        String amount,
        String currency,
        String description,
        TransferStatus status,
        Instant createdAt) {
}
