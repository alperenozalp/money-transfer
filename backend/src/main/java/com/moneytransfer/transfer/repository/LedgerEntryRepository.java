package com.moneytransfer.transfer.repository;

import java.util.UUID;

import com.moneytransfer.transfer.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
}
