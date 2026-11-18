package com.moneytransfer.transfer.repository;

import java.util.Optional;
import java.util.UUID;

import com.moneytransfer.transfer.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransferRepository extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

    Optional<Transfer> findBySender_IdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    Optional<Transfer> findBySender_IdAndIdAndSourceAccountIsNotNull(UUID senderId, UUID id);
}
