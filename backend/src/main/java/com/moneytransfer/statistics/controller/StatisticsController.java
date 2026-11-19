package com.moneytransfer.statistics.controller;

import java.util.List;

import com.moneytransfer.shared.api.ApiResponse;
import com.moneytransfer.statistics.dto.MonthlyTransferStatisticsResponse;
import com.moneytransfer.statistics.service.StatisticsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/monthly-transfers")
    public ApiResponse<List<MonthlyTransferStatisticsResponse>> monthlyTransfers(
            @AuthenticationPrincipal Jwt jwt) {
        return new ApiResponse<>(statisticsService.monthlyTransfers(jwt.getSubject()));
    }
}
