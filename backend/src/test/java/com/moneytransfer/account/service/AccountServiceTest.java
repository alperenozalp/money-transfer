package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.moneytransfer.account.config.TransferLimitProperties;
import com.moneytransfer.account.dto.AccountResponse;
import com.moneytransfer.account.dto.TransferLimitsResponse;
import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.account.repository.TransferLimitRepository;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String SUBJECT = "identity-subject-1";
    private static final Instant NOW = Instant.parse("2026-11-18T23:30:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransferLimitRepository transferLimitRepository;

    private AccountService accountService;
    private TransferLimitProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TransferLimitProperties(
                ZoneId.of("Europe/Istanbul"),
                new TransferLimitProperties.Limits(
                        new BigDecimal("50000.00"),
                        new BigDecimal("100000.00"),
                        new BigDecimal("500000.00")));
        accountService = new AccountService(
                accountRepository,
                customerRepository,
                transferLimitRepository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenProvisionedCustomer() {
        when(customerRepository.existsByIdentitySubject(SUBJECT)).thenReturn(true);
    }

    @Test
    void listsOnlyTheAuthenticatedCustomersAccountsWithDecimalSafeBalances() {
        givenProvisionedCustomer();
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(java.util.UUID.randomUUID());
        when(account.getIban()).thenReturn("TR000000000000000000000000");
        when(account.getCurrency()).thenReturn("TRY");
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getBalance()).thenReturn(new BigDecimal("1234.50"));
        when(accountRepository.findAllByCustomer_IdentitySubjectOrderByIdAsc(SUBJECT))
                .thenReturn(List.of(account));

        List<AccountResponse> result = accountService.listAccounts(SUBJECT);

        assertEquals(1, result.size());
        assertEquals("1234.50", result.getFirst().balance());
        verify(accountRepository).findAllByCustomer_IdentitySubjectOrderByIdAsc(SUBJECT);
    }

    @Test
    void calculatesDailyAndMonthlyLimitsUsingIstanbulCalendarBoundaries() {
        givenProvisionedCustomer();
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

        TransferLimitsResponse response = accountService.getTransferLimits(SUBJECT);

        assertEquals("50000.00", response.perTransferMaximum());
        assertEquals("99750.00", response.dailyRemaining());
        assertEquals("498750.00", response.monthlyRemaining());
    }

    @Test
    void remainingLimitsAreClampedAtZero() {
        givenProvisionedCustomer();
        when(transferLimitRepository.sumCompletedOutgoing(
                        SUBJECT,
                        Instant.parse("2026-11-18T21:00:00Z"),
                        Instant.parse("2026-11-19T21:00:00Z")))
                .thenReturn(new BigDecimal("100001.00"));
        when(transferLimitRepository.sumCompletedOutgoing(
                        SUBJECT,
                        Instant.parse("2026-10-31T21:00:00Z"),
                        Instant.parse("2026-11-30T21:00:00Z")))
                .thenReturn(new BigDecimal("500001.00"));

        TransferLimitsResponse response = accountService.getTransferLimits(SUBJECT);

        assertEquals("0.00", response.dailyRemaining());
        assertEquals("0.00", response.monthlyRemaining());
    }

    @Test
    void rejectsSubjectsWithoutProvisionedCustomers() {
        when(customerRepository.existsByIdentitySubject("unknown-subject")).thenReturn(false);

        assertThrows(
                CustomerNotProvisionedException.class,
                () -> accountService.listAccounts("unknown-subject"));
        verify(accountRepository, never()).findAllByCustomer_IdentitySubjectOrderByIdAsc("unknown-subject");
    }
}
