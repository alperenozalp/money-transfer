package com.moneytransfer.account.dto;

import java.util.UUID;

import com.moneytransfer.account.model.AccountStatus;

public record AccountResponse(
        UUID id,
        String iban,
        String currency,
        AccountStatus status,
        String balance) {
}
