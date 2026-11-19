package com.moneytransfer.transfer.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneytransfer.shared.api.ApiResponse;
import com.moneytransfer.transfer.dto.CreateTransferRequest;
import com.moneytransfer.transfer.dto.TransferDirection;
import com.moneytransfer.transfer.dto.TransferResponse;
import com.moneytransfer.transfer.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        TransferService.CreationResult result =
                transferService.create(jwt.getSubject(), idempotencyKey, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ApiResponse<>(result.transfer()));
    }

    @GetMapping
    public ApiResponse<List<TransferResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) TransferDirection direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TransferResponse> result =
                transferService.listForCustomer(jwt.getSubject(), direction, startDate, endDate, page, size);
        return new ApiResponse<>(
                result.getContent(),
                Map.of(
                        "page", result.getNumber(),
                        "size", result.getSize(),
                        "totalElements", result.getTotalElements(),
                        "totalPages", result.getTotalPages()));
    }

    @GetMapping("/{transferId}")
    public ApiResponse<TransferResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transferId) {
        return new ApiResponse<>(transferService.getForCustomer(jwt.getSubject(), transferId));
    }
}
