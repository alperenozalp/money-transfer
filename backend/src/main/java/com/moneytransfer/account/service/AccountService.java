package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import com.moneytransfer.account.config.TransferLimitProperties;
import com.moneytransfer.account.dto.AccountResponse;
import com.moneytransfer.account.dto.TransferLimitsResponse;
import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.account.repository.TransferLimitRepository;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final String CURRENCY = "TRY";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransferLimitRepository transferLimitRepository;
    private final TransferLimitProperties properties;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            TransferLimitRepository transferLimitRepository,
            TransferLimitProperties properties,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.transferLimitRepository = transferLimitRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(String identitySubject) {
        requireProvisionedCustomer(identitySubject);
        return accountRepository.findAllByCustomer_IdentitySubjectOrderByIdAsc(identitySubject)
                .stream()
                .map(account -> new AccountResponse(
                        account.getId(),
                        account.getIban(),
                        account.getCurrency(),
                        account.getStatus(),
                        formatMoney(account.getBalance())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TransferLimitsResponse getTransferLimits(String identitySubject) {
        requireProvisionedCustomer(identitySubject);
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

        return new TransferLimitsResponse(
                CURRENCY,
                formatMoney(properties.limits().perTransfer()),
                formatMoney(remaining(properties.limits().daily(), dailySpent)),
                formatMoney(remaining(properties.limits().monthly(), monthlySpent)));
    }

    private void requireProvisionedCustomer(String identitySubject) {
        if (!customerRepository.existsByIdentitySubject(identitySubject)) {
            throw new CustomerNotProvisionedException();
        }
    }

    private BigDecimal remaining(BigDecimal limit, BigDecimal spent) {
        return limit.subtract(spent).max(BigDecimal.ZERO);
    }

    private String formatMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
