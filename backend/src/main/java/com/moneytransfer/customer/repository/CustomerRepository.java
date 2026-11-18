package com.moneytransfer.customer.repository;

import java.util.UUID;
import java.util.Optional;

import com.moneytransfer.customer.model.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByIdentitySubject(String identitySubject);

    Optional<Customer> findByIdentitySubject(String identitySubject);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.identitySubject = :identitySubject")
    java.util.Optional<Customer> findByIdentitySubjectForUpdate(
            @Param("identitySubject") String identitySubject);
}
