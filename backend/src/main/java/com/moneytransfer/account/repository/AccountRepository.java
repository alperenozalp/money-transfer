package com.moneytransfer.account.repository;

import java.util.List;
import java.util.UUID;

import com.moneytransfer.account.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByCustomer_IdentitySubjectOrderByIdAsc(String identitySubject);
}
