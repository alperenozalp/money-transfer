package com.moneytransfer.transfer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.account.service.TransferLimitService;
import com.moneytransfer.account.service.TransferLimitService.Snapshot;
import com.moneytransfer.customer.exception.CustomerNotProvisionedException;
import com.moneytransfer.customer.model.Customer;
import com.moneytransfer.customer.repository.CustomerRepository;
import com.moneytransfer.transfer.dto.CreateTransferRequest;
import com.moneytransfer.transfer.dto.TransferDirection;
import com.moneytransfer.transfer.dto.TransferResponse;
import com.moneytransfer.transfer.exception.IdempotencyConflictException;
import com.moneytransfer.transfer.exception.InvalidTransferQueryException;
import com.moneytransfer.transfer.exception.TransferNotFoundException;
import com.moneytransfer.transfer.exception.TransferRuleException;
import com.moneytransfer.transfer.model.LedgerDirection;
import com.moneytransfer.transfer.model.LedgerEntry;
import com.moneytransfer.transfer.model.Transfer;
import com.moneytransfer.transfer.model.TransferStatus;
import com.moneytransfer.transfer.repository.LedgerEntryRepository;
import com.moneytransfer.transfer.repository.TransferRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final String CURRENCY = "TRY";
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferLimitService transferLimitService;
    private final TransferRepository transferRepository;
    private final Clock clock;

    public TransferService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TransferLimitService transferLimitService,
            TransferRepository transferRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transferLimitService = transferLimitService;
        this.transferRepository = transferRepository;
        this.clock = clock;
    }

    @Transactional
    public CreationResult create(
            String identitySubject,
            String idempotencyKey,
            CreateTransferRequest request) {
        Customer sender = customerRepository.findByIdentitySubjectForUpdate(identitySubject)
                .orElseThrow(CustomerNotProvisionedException::new);

        validateAmount(request.amount());
        String normalizedIban = normalizeIban(request.receiverIban());
        String description = normalizeDescription(request.description());
        String fingerprint = fingerprint(request.sourceAccountId(), normalizedIban, request.amount(), description);

        var existingTransfer = transferRepository.findBySender_IdAndIdempotencyKey(sender.getId(), idempotencyKey);
        if (existingTransfer.isPresent()) {
            Transfer existing = existingTransfer.get();
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            return new CreationResult(toResponse(existing, TransferDirection.OUTGOING), true);
        }

        Account requestedSource = accountRepository
                .findByIdAndCustomer_Id(request.sourceAccountId(), sender.getId())
                .orElseThrow(() -> accountNotAvailable());
        Account requestedDestination = accountRepository.findByIban(normalizedIban)
                .orElseThrow(() -> accountNotAvailable());

        if (requestedSource.getId().equals(requestedDestination.getId())) {
            throw new TransferRuleException("SAME_ACCOUNT", "Source and destination accounts must be different.");
        }

        List<UUID> accountIds = List.of(requestedSource.getId(), requestedDestination.getId()).stream()
                .sorted()
                .toList();
        Map<UUID, Account> lockedAccounts = accountRepository.findAllByIdInOrderByIdForUpdate(accountIds)
                .stream()
                .collect(Collectors.toMap(Account::getId, account -> account));
        Account source = lockedAccounts.get(requestedSource.getId());
        Account destination = lockedAccounts.get(requestedDestination.getId());
        if (source == null || destination == null) {
            throw accountNotAvailable();
        }

        validateAccounts(source, destination);
        Snapshot limits = transferLimitService.currentFor(identitySubject);
        validateLimits(request.amount(), limits);
        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new TransferRuleException("INSUFFICIENT_FUNDS", "The source account has insufficient funds.");
        }

        Instant now = clock.instant();
        Transfer transfer = transferRepository.save(new Transfer(
                sender,
                source,
                destination,
                request.amount(),
                CURRENCY,
                description,
                TransferStatus.COMPLETED,
                now,
                idempotencyKey,
                fingerprint));

        source.debit(request.amount());
        destination.credit(request.amount());
        ledgerEntryRepository.saveAll(List.of(
                new LedgerEntry(transfer, source, LedgerDirection.DEBIT, request.amount(), now),
                new LedgerEntry(transfer, destination, LedgerDirection.CREDIT, request.amount(), now)));

        return new CreationResult(toResponse(transfer, TransferDirection.OUTGOING), false);
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> listForCustomer(
            String identitySubject,
            TransferDirection direction,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int requestedSize) {
        Customer sender = customerRepository.findByIdentitySubject(identitySubject)
                .orElseThrow(CustomerNotProvisionedException::new);
        if (page < 0 || requestedSize < 1) {
            throw new InvalidTransferQueryException("Page must be non-negative and size must be positive.");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidTransferQueryException("startDate must not be after endDate.");
        }

        ZoneId zone = transferLimitServiceZone();
        Instant start = startDate == null ? null : startOfDay(startDate, zone);
        Instant end = endDate == null ? null : startOfDayAfter(endDate, zone);

        Specification<Transfer> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.isNotNull(root.get("sourceAccount")));
            predicates.add(criteriaBuilder.isNotNull(root.get("destinationAccount")));
            Join<Transfer, Account> destination = root.join("destinationAccount", JoinType.LEFT);
            Join<Account, Customer> destinationCustomer = destination.join("customer", JoinType.LEFT);
            Predicate outgoing = criteriaBuilder.equal(root.get("sender").get("id"), sender.getId());
            Predicate incoming = criteriaBuilder.and(
                    criteriaBuilder.equal(destinationCustomer.get("id"), sender.getId()),
                    criteriaBuilder.notEqual(root.get("sender").get("id"), sender.getId()));
            if (direction == TransferDirection.OUTGOING) {
                predicates.add(outgoing);
            } else if (direction == TransferDirection.INCOMING) {
                predicates.add(incoming);
            } else {
                predicates.add(criteriaBuilder.or(outgoing, incoming));
            }
            if (start != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<Instant>get("createdAt"), start));
            }
            if (end != null) {
                predicates.add(criteriaBuilder.lessThan(root.<Instant>get("createdAt"), end));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        return transferRepository.findAll(
                        specification,
                        PageRequest.of(
                                page,
                                Math.min(requestedSize, MAX_PAGE_SIZE),
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(transfer -> toResponse(
                        transfer,
                        transfer.getSender().getId().equals(sender.getId())
                                ? TransferDirection.OUTGOING
                                : TransferDirection.INCOMING));
    }

    @Transactional(readOnly = true)
    public TransferResponse getForCustomer(String identitySubject, UUID transferId) {
        Customer customer = customerRepository.findByIdentitySubject(identitySubject)
                .orElseThrow(CustomerNotProvisionedException::new);
        Transfer transfer = transferRepository.findAccessibleToCustomer(transferId, customer.getId())
                .orElseThrow(TransferNotFoundException::new);
        TransferDirection direction = transfer.getSender().getId().equals(customer.getId())
                ? TransferDirection.OUTGOING
                : TransferDirection.INCOMING;
        return toResponse(transfer, direction);
    }

    private ZoneId transferLimitServiceZone() {
        return transferLimitService.getBusinessTimeZone();
    }

    private Instant startOfDay(LocalDate date, ZoneId zone) {
        try {
            return date.atStartOfDay(zone).toInstant();
        } catch (DateTimeException exception) {
            throw new InvalidTransferQueryException("The requested date is outside the supported range.");
        }
    }

    private Instant startOfDayAfter(LocalDate date, ZoneId zone) {
        try {
            return date.plusDays(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeException exception) {
            throw new InvalidTransferQueryException("The requested date is outside the supported range.");
        }
    }

    private void validateAccounts(Account source, Account destination) {
        if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferRuleException("ACCOUNT_NOT_AVAILABLE", "Both accounts must be active.");
        }
        if (!CURRENCY.equals(source.getCurrency()) || !CURRENCY.equals(destination.getCurrency())) {
            throw new TransferRuleException("UNSUPPORTED_CURRENCY", "Only TRY accounts can be used for transfers.");
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new TransferRuleException("INVALID_AMOUNT", "Amount must be positive with at most two decimal places.");
        }
    }

    private void validateLimits(BigDecimal amount, Snapshot limits) {
        if (amount.compareTo(limits.perTransferMaximum()) > 0) {
            throw new TransferRuleException("PER_TRANSFER_LIMIT_EXCEEDED", "The per-transfer limit was exceeded.");
        }
        if (limits.dailySpent().add(amount).compareTo(limits.dailyMaximum()) > 0) {
            throw new TransferRuleException("DAILY_LIMIT_EXCEEDED", "The daily transfer limit would be exceeded.");
        }
        if (limits.monthlySpent().add(amount).compareTo(limits.monthlyMaximum()) > 0) {
            throw new TransferRuleException("MONTHLY_LIMIT_EXCEEDED", "The monthly transfer limit would be exceeded.");
        }
    }

    private TransferRuleException accountNotAvailable() {
        return new TransferRuleException("ACCOUNT_NOT_AVAILABLE", "The requested account is not available.");
    }

    private String normalizeIban(String iban) {
        return iban.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private String fingerprint(UUID sourceAccountId, String receiverIban, BigDecimal amount, String description) {
        String canonicalAmount = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        String canonicalRequest = fingerprintField(sourceAccountId.toString())
                + fingerprintField(receiverIban)
                + fingerprintField(canonicalAmount)
                + fingerprintField(description == null ? "" : description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for transfer request fingerprints.", exception);
        }
    }

    private String fingerprintField(String value) {
        return value.length() + ":" + value;
    }

    private TransferResponse toResponse(Transfer transfer, TransferDirection direction) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccount().getId(),
                transfer.getSourceAccount().getIban(),
                transfer.getDestinationAccount().getIban(),
                direction,
                transfer.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                transfer.getCurrency(),
                transfer.getDescription(),
                transfer.getStatus(),
                transfer.getCreatedAt());
    }

    public record CreationResult(TransferResponse transfer, boolean replayed) {
    }
}
