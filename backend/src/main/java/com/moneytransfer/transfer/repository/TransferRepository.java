package com.moneytransfer.transfer.repository;

import java.util.Optional;
import java.util.UUID;

import com.moneytransfer.transfer.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

    Optional<Transfer> findBySender_IdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    Optional<Transfer> findBySender_IdAndIdAndSourceAccountIsNotNull(UUID senderId, UUID id);

    @Query("""
            select transfer from Transfer transfer
            where transfer.id = :transferId
                and transfer.sourceAccount is not null
                and transfer.destinationAccount is not null
                and (transfer.sender.id = :customerId
                    or transfer.destinationAccount.customer.id = :customerId)
            """)
    Optional<Transfer> findAccessibleToCustomer(
            @Param("transferId") UUID transferId,
            @Param("customerId") UUID customerId);
}
