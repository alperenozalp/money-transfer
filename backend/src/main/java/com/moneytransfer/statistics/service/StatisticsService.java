package com.moneytransfer.statistics.service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moneytransfer.account.service.TransferLimitService;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.customer.repository.CustomerRepository;
import com.moneytransfer.statistics.dto.MonthlyTransferStatisticsResponse;
import com.moneytransfer.statistics.repository.StatisticsRepository;
import com.moneytransfer.statistics.repository.StatisticsRepository.MonthTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsService {

    private static final int MONTH_COUNT = 6;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Clock clock;
    private final CustomerRepository customerRepository;
    private final StatisticsRepository statisticsRepository;
    private final TransferLimitService transferLimitService;

    public StatisticsService(
            Clock clock,
            CustomerRepository customerRepository,
            StatisticsRepository statisticsRepository,
            TransferLimitService transferLimitService) {
        this.clock = clock;
        this.customerRepository = customerRepository;
        this.statisticsRepository = statisticsRepository;
        this.transferLimitService = transferLimitService;
    }

    @Transactional(readOnly = true)
    public List<MonthlyTransferStatisticsResponse> monthlyTransfers(String identitySubject) {
        if (!customerRepository.existsByIdentitySubject(identitySubject)) {
            throw new CustomerNotProvisionedException();
        }

        ZoneId zone = transferLimitService.getBusinessTimeZone();
        YearMonth currentMonth = YearMonth.from(LocalDate.now(clock.withZone(zone)));
        YearMonth firstMonth = currentMonth.minusMonths(MONTH_COUNT - 1);
        Map<YearMonth, MonthTotal> totals = new HashMap<>();
        statisticsRepository.monthlyTotals(
                        identitySubject,
                        zone,
                        firstMonth.atDay(1).atStartOfDay(zone).toInstant(),
                        currentMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())
                .forEach(total -> totals.put(
                        YearMonth.from(total.monthStart()),
                        total));

        return java.util.stream.IntStream.range(0, MONTH_COUNT)
                .mapToObj(index -> currentMonth.minusMonths(index))
                .map(month -> toResponse(month, totals.get(month)))
                .toList();
    }

    private MonthlyTransferStatisticsResponse toResponse(YearMonth month, MonthTotal total) {
        long count = total == null ? 0 : total.completedTransferCount();
        String amount = total == null
                ? "0.00"
                : total.completedOutgoingAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        return new MonthlyTransferStatisticsResponse(month.format(MONTH_FORMAT), count, amount);
    }
}
