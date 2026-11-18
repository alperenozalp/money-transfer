package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.moneytransfer.account.config.TransferLimitProperties;
import com.moneytransfer.account.repository.TransferLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferLimitServiceTest {

    private static final String SUBJECT = "identity-subject-1";
    private static final Instant NOW = Instant.parse("2026-11-18T23:30:00Z");

    @Mock
    private TransferLimitRepository transferLimitRepository;

    private TransferLimitService transferLimitService;

    @BeforeEach
    void setUp() {
        TransferLimitProperties properties = new TransferLimitProperties(
                ZoneId.of("Europe/Istanbul"),
                new TransferLimitProperties.Limits(
                        new BigDecimal("50000.00"),
                        new BigDecimal("100000.00"),
                        new BigDecimal("500000.00")));
        transferLimitService = new TransferLimitService(
                transferLimitRepository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void calculatesDailyAndMonthlyUsageUsingIstanbulCalendarBoundaries() {
        when(transferLimitRepository.sumCompletedOutgoing(
                        SUBJECT,
                        Instant.parse("2026-11-18T21:00:00Z"),
                        Instant.parse("2026-11-19T21:00:00Z")))
                .thenReturn(new BigDecimal("250.00"));
        when(transferLimitRepository.sumCompletedOutgoing(
                        SUBJECT,
                        Instant.parse("2026-10-31T21:00:00Z"),
                        Instant.parse("2026-11-30T21:00:00Z")))
                .thenReturn(new BigDecimal("1250.00"));

        TransferLimitService.Snapshot snapshot = transferLimitService.currentFor(SUBJECT);

        assertEquals(new BigDecimal("99750.00"), snapshot.dailyRemaining());
        assertEquals(new BigDecimal("498750.00"), snapshot.monthlyRemaining());
        verify(transferLimitRepository).sumCompletedOutgoing(
                SUBJECT,
                Instant.parse("2026-11-18T21:00:00Z"),
                Instant.parse("2026-11-19T21:00:00Z"));
    }
}
