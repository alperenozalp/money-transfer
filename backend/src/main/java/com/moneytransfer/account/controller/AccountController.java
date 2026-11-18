package com.moneytransfer.account.controller;

import java.util.List;

import com.moneytransfer.account.dto.AccountResponse;
import com.moneytransfer.account.dto.TransferLimitsResponse;
import com.moneytransfer.account.service.AccountService;
import com.moneytransfer.shared.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ApiResponse<List<AccountResponse>> listAccounts(@AuthenticationPrincipal Jwt jwt) {
        return new ApiResponse<>(accountService.listAccounts(jwt.getSubject()));
    }

    @GetMapping("/transfer-limits")
    public ApiResponse<TransferLimitsResponse> getTransferLimits(@AuthenticationPrincipal Jwt jwt) {
        return new ApiResponse<>(accountService.getTransferLimits(jwt.getSubject()));
    }
}
