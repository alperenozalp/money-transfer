package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.util.List;

import com.moneytransfer.account.dto.AccountResponse;
import com.moneytransfer.account.dto.TransferLimitsResponse;
import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.account.service.TransferLimitService.Snapshot;
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

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private TransferLimitService transferLimitService;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, customerRepository, transferLimitService);
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
        when(transferLimitService.currentFor(SUBJECT)).thenReturn(new Snapshot(
                new BigDecimal("50000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("250.00"),
                new BigDecimal("1250.00")));

        TransferLimitsResponse response = accountService.getTransferLimits(SUBJECT);

        assertEquals("50000.00", response.perTransferMaximum());
        assertEquals("99750.00", response.dailyRemaining());
        assertEquals("498750.00", response.monthlyRemaining());
    }

    @Test
    void remainingLimitsAreClampedAtZero() {
        givenProvisionedCustomer();
        when(transferLimitService.currentFor(SUBJECT)).thenReturn(new Snapshot(
                new BigDecimal("50000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("100001.00"),
                new BigDecimal("500001.00")));

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
