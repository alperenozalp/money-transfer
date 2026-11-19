package com.moneytransfer.security.local;

import java.util.Optional;

import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.customer.model.Customer;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalDemoDataSeederTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    private LocalDemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new LocalDemoDataSeeder(accountRepository, customerRepository);
        when(customerRepository.findByIdentitySubject(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String subject = invocation.getArgument(0);
                    return Optional.of(new Customer(subject));
                });
    }

    @Test
    void createsOnlyTheMissingSyntheticAccounts() {
        when(accountRepository.findByIban(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());

        seeder.run();

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(3)).save(accountCaptor.capture());
        assertEquals(
                java.util.List.of(
                        "TR0000000000000000000000001",
                        "TR0000000000000000000000003",
                        "TR0000000000000000000000004"),
                accountCaptor.getAllValues().stream().map(Account::getIban).toList());
    }

    @Test
    void doesNotOverwriteExistingDemoAccountsOnRestart() {
        when(accountRepository.findByIban(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Account.class)));

        seeder.run();

        verify(accountRepository, never()).save(org.mockito.ArgumentMatchers.any(Account.class));
    }
}
