package com.moneytransfer.security.local;

import java.math.BigDecimal;

import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.customer.model.Customer;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local & !prod")
public class LocalDemoDataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public LocalDemoDataSeeder(AccountRepository accountRepository, CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        for (LocalDemoUsers.DemoUser demoUser : LocalDemoUsers.ALL) {
            Customer customer = customerRepository.findByIdentitySubject(demoUser.subject())
                    .orElseGet(() -> customerRepository.save(new Customer(demoUser.subject())));
            for (LocalDemoUsers.DemoAccount demoAccount : demoUser.accounts()) {
                addAccountIfMissing(customer, demoAccount.iban(), demoAccount.balance());
            }
        }
    }

    private void addAccountIfMissing(Customer customer, String iban, String balance) {
        if (accountRepository.findByIban(iban).isEmpty()) {
            accountRepository.save(new Account(
                    customer,
                    iban,
                    "TRY",
                    AccountStatus.ACTIVE,
                    new BigDecimal(balance)));
        }
    }
}
