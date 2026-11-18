package com.moneytransfer.account.repository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.moneytransfer.account.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByCustomer_IdentitySubjectOrderByIdAsc(String identitySubject);

    Optional<Account> findByIdAndCustomer_Id(UUID id, UUID customerId);

    Optional<Account> findByIban(String iban);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdInOrderByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
