package com.moneytransfer.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.moneytransfer.account.dto.AccountResponse;
import com.moneytransfer.account.dto.TransferLimitsResponse;
import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.account.service.TransferLimitService.Snapshot;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final String CURRENCY = "TRY";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final TransferLimitService transferLimitService;

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            TransferLimitService transferLimitService) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.transferLimitService = transferLimitService;
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
        Snapshot limits = transferLimitService.currentFor(identitySubject);

        return new TransferLimitsResponse(
                CURRENCY,
                formatMoney(limits.perTransferMaximum()),
                formatMoney(limits.dailyRemaining()),
                formatMoney(limits.monthlyRemaining()));
    }

    private void requireProvisionedCustomer(String identitySubject) {
        if (!customerRepository.existsByIdentitySubject(identitySubject)) {
            throw new CustomerNotProvisionedException();
        }
    }

    private String formatMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
