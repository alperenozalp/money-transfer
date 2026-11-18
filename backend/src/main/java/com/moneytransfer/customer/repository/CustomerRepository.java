package com.moneytransfer.customer.repository;

import java.util.UUID;

import com.moneytransfer.customer.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByIdentitySubject(String identitySubject);
}
