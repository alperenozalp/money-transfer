package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import com.moneytransfer.account.config.TransferLimitProperties;
import com.moneytransfer.account.repository.TransferLimitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferLimitService {

    private final TransferLimitRepository transferLimitRepository;
    private final TransferLimitProperties properties;
    private final Clock clock;

    public TransferLimitService(
            TransferLimitRepository transferLimitRepository,
            TransferLimitProperties properties,
            Clock clock) {
        this.transferLimitRepository = transferLimitRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Snapshot currentFor(String identitySubject) {
        ZoneId zone = properties.businessTimeZone();
        LocalDate businessDate = LocalDate.now(clock.withZone(zone));
        YearMonth businessMonth = YearMonth.from(businessDate);

        BigDecimal dailySpent = transferLimitRepository.sumCompletedOutgoing(
                identitySubject,
                businessDate.atStartOfDay(zone).toInstant(),
                businessDate.plusDays(1).atStartOfDay(zone).toInstant());
        BigDecimal monthlySpent = transferLimitRepository.sumCompletedOutgoing(
                identitySubject,
                businessMonth.atDay(1).atStartOfDay(zone).toInstant(),
                businessMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());

        return new Snapshot(
                properties.limits().perTransfer(),
                properties.limits().daily(),
                properties.limits().monthly(),
                dailySpent,
                monthlySpent);
    }

    public ZoneId getBusinessTimeZone() {
        return properties.businessTimeZone();
    }

    public record Snapshot(
            BigDecimal perTransferMaximum,
            BigDecimal dailyMaximum,
            BigDecimal monthlyMaximum,
            BigDecimal dailySpent,
            BigDecimal monthlySpent) {

        public BigDecimal dailyRemaining() {
            return remaining(dailyMaximum, dailySpent);
        }

        public BigDecimal monthlyRemaining() {
            return remaining(monthlyMaximum, monthlySpent);
        }

        private BigDecimal remaining(BigDecimal limit, BigDecimal spent) {
            return limit.subtract(spent).max(BigDecimal.ZERO);
        }
    }
}
