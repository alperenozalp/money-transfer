package com.moneytransfer.transfer.controller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.customer.model.Customer;
import com.moneytransfer.customer.repository.CustomerRepository;
import com.moneytransfer.transfer.dto.CreateTransferRequest;
import com.moneytransfer.transfer.exception.TransferRuleException;
import com.moneytransfer.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.hasSize;

@SpringBootTest(properties = {
        "transfer.limits.daily=60000.00",
        "transfer.limits.monthly=50000.00"
})
@AutoConfigureMockMvc
@Import(TransferApiIT.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TransferApiIT {

    private static final Instant NOW = Instant.parse("2026-11-18T23:30:00Z");
    private static final String ALICE_SUBJECT = "oidc-subject-alice";
    private static final String BOB_SUBJECT = "oidc-subject-bob";
    private static final String SENDER_IBAN = "TR0000000000000000000000001";
    private static final String RECEIVER_IBAN = "TR0000000000000000000000002";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferService transferService;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM transfers");
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void createsAtomicDebitAndCreditAndReplaysIdempotently() throws Exception {
        Fixture fixture = fixture();
        String key = "transfer-once";
        String requestBody = request(fixture.source().getId(), RECEIVER_IBAN, "125.00");

        String firstResponse = mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.amount").value("125.00"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String transferId = objectMapper.readTree(firstResponse).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(transferId));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "126.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(new BigDecimal("99875.00"), accountRepository.findById(fixture.source().getId()).orElseThrow()
                .getBalance());
        assertEquals(new BigDecimal("225.00"), accountRepository.findById(fixture.destination().getId()).orElseThrow()
                .getBalance());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    }

    @Test
    void transferHistoryIncludesIncomingAndOutgoingTransfersAndUsesInclusiveBusinessDates() throws Exception {
        Fixture fixture = fixture();
        String response = mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "history-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String transferId = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("direction", "OUTGOING")
                        .param("startDate", "2026-11-19")
                        .param("endDate", "2026-11-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].direction").value("OUTGOING"))
                .andExpect(jsonPath("$.data[0].sourceIban").value(SENDER_IBAN))
                .andExpect(jsonPath("$.data[0].receiverIban").value(RECEIVER_IBAN))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT)))
                        .param("direction", "INCOMING")
                        .param("startDate", "2026-11-19")
                        .param("endDate", "2026-11-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].direction").value("INCOMING"))
                .andExpect(jsonPath("$.data[0].sourceIban").value(SENDER_IBAN))
                .andExpect(jsonPath("$.data[0].receiverIban").value(RECEIVER_IBAN))
                .andExpect(jsonPath("$.data[0].amount").value("10.00"));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("direction", "INCOMING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT)))
                        .param("direction", "OUTGOING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(0));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("startDate", "2026-11-18")
                        .param("endDate", "2026-11-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/v1/statistics/monthly-transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].month").value("2026-11"))
                .andExpect(jsonPath("$.data[0].completedTransferCount").value(1))
                .andExpect(jsonPath("$.data[0].completedOutgoingAmount").value("10.00"))
                .andExpect(jsonPath("$.data[1].completedTransferCount").value(0))
                .andExpect(jsonPath("$.data[1].completedOutgoingAmount").value("0.00"));

        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("INCOMING"));

        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(transferId));

        customerRepository.saveAndFlush(new Customer("oidc-subject-charlie"));
        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject("oidc-subject-charlie"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("startDate", "2026-11-20")
                        .param("endDate", "2026-11-19"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertNotEquals(fixture.alice().getId(), fixture.bob().getId());
    }

    @Test
    void rejectsInvalidAmountsAndInsufficientFundsWithoutPersistingAnything() throws Exception {
        Fixture fixture = fixture("10.00");

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "over-per-transfer-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "50000.01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PER_TRANSFER_LIMIT_EXCEEDED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "insufficient-funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "11.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "invalid-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(new BigDecimal("10.00"), accountRepository.findById(fixture.source().getId()).orElseThrow()
                .getBalance());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    }

    @Test
    void enforcesMonthlyLimitAcrossEarlierBusinessDays() throws Exception {
        Fixture fixture = fixture();
        jdbcTemplate.update(
                "INSERT INTO transfers (id, sender_customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                fixture.alice().getId(),
                new BigDecimal("20000.00"),
                "COMPLETED",
                Timestamp.from(NOW.minusSeconds(24 * 60 * 60)));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "monthly-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "40000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MONTHLY_LIMIT_EXCEEDED"));

        assertEquals(new BigDecimal("100000.00"), accountRepository.findById(fixture.source().getId()).orElseThrow()
                .getBalance());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: CLOSED source or destination account
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferInvolvingClosedAccount() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob   = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account source = accountRepository.saveAndFlush(new Account(
                alice, SENDER_IBAN, "TRY", AccountStatus.CLOSED, new BigDecimal("1000.00")));
        accountRepository.saveAndFlush(account(bob, RECEIVER_IBAN, "100.00"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "closed-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(source.getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_AVAILABLE"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Non-TRY currency account transfer
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferWithNonTryCurrencyAccount() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob   = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account source = accountRepository.saveAndFlush(new Account(
                alice, SENDER_IBAN, "USD", AccountStatus.ACTIVE, new BigDecimal("1000.00")));
        accountRepository.saveAndFlush(account(bob, RECEIVER_IBAN, "100.00"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "usd-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(source.getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CURRENCY"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Invalid pagination parameters (negative page or zero size)
    // -------------------------------------------------------------------------
    @Test
    void rejectsInvalidPaginationParameters() throws Exception {
        customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Transfer to the same account (SAME_ACCOUNT)
    // -------------------------------------------------------------------------
    @Test
    void rejectsSameAccountTransfer() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "same-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), SENDER_IBAN, "100.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SAME_ACCOUNT"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Transfer using a source account owned by another customer
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferFromAnotherCustomersAccount() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT)))
                        .header("Idempotency-Key", "wrong-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_AVAILABLE"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Transfer to an unknown/non-existent IBAN
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferToUnknownIban() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "unknown-iban")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), "TR9999999999999999999999999", "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_AVAILABLE"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Transfer from an INACTIVE source account
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferFromInactiveSourceAccount() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob   = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account  source = accountRepository.saveAndFlush(new Account(
                alice, SENDER_IBAN, "TRY", AccountStatus.INACTIVE, new BigDecimal("5000.00")));
        accountRepository.saveAndFlush(account(bob, RECEIVER_IBAN, "100.00"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "inactive-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(source.getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_AVAILABLE"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Transfer to an INACTIVE destination account
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferToInactiveDestinationAccount() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob   = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account  source = accountRepository.saveAndFlush(account(alice, SENDER_IBAN, "5000.00"));
        accountRepository.saveAndFlush(new Account(
                bob, RECEIVER_IBAN, "TRY", AccountStatus.INACTIVE, new BigDecimal("100.00")));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "inactive-dest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(source.getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_AVAILABLE"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Amount with more than two decimal places
    // -------------------------------------------------------------------------
    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "bad-decimal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "10.001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Negative amount validation
    // -------------------------------------------------------------------------
    @Test
    void rejectsNegativeAmount() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "negative-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Missing Idempotency-Key header
    // -------------------------------------------------------------------------
    @Test
    void rejectsRequestWithoutIdempotencyKey() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Edge case: Unprovisioned customer attempt
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferFromUnprovisionedCustomer() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject("unknown-subject")))
                        .header("Idempotency-Key", "unprovisioned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": "%s",
                                  "receiverIban": "%s",
                                  "amount": "10.00"
                                }
                                """.formatted(UUID.randomUUID(), RECEIVER_IBAN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_NOT_PROVISIONED"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Unauthenticated request
    // -------------------------------------------------------------------------
    @Test
    void rejectsUnauthenticatedTransferRequest() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "no-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","receiverIban":"%s","amount":"10.00"}
                                """.formatted(UUID.randomUUID(), RECEIVER_IBAN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Recipient user (Bob) can also initiate transfers
    // -------------------------------------------------------------------------
    @Test
    void recipientCanAlsoInitiateATransfer() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob   = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account aliceAccount = accountRepository.saveAndFlush(account(alice, SENDER_IBAN, "100.00"));
        Account bobAccount   = accountRepository.saveAndFlush(account(bob, RECEIVER_IBAN, "5000.00"));

        // Bob -> Alice
        String response = mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT)))
                        .header("Idempotency-Key", "bob-sends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(bobAccount.getId(), SENDER_IBAN, "500.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.amount").value("500.00"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(new BigDecimal("4500.00"),
                accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("600.00"),
                accountRepository.findById(aliceAccount.getId()).orElseThrow().getBalance());

        String transferId = objectMapper.readTree(response).path("data").path("id").asText();

        // Bob sees the transfer as OUTGOING
        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject(BOB_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("OUTGOING"));

        // Alice sees the transfer as INCOMING
        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("INCOMING"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Legacy transfers with NULL source_account_id do not affect limits
    // -------------------------------------------------------------------------
    @Test
    void legacyTransfersWithNullSourceAccountDoNotCountTowardLimits() throws Exception {
        Fixture fixture = fixture();

        // Insert legacy transfer without source_account_id (V1 format)
        jdbcTemplate.update(
                "INSERT INTO transfers (id, sender_customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                fixture.alice().getId(),
                new BigDecimal("55000.00"),
                "COMPLETED",
                Timestamp.from(NOW.minusSeconds(60)));

        // New transfer should succeed and ignore the legacy transfer in limit calculations
        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "legacy-no-effect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "10.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertEquals(new BigDecimal("99990.00"),
                accountRepository.findById(fixture.source().getId()).orElseThrow().getBalance());
    }

    // -------------------------------------------------------------------------
    // Edge case: Pagination - out of range page returns empty list
    // -------------------------------------------------------------------------
    @Test
    void transferListReturnsEmptyPageForOutOfRangePage() throws Exception {
        Fixture fixture = fixture();
        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "page-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "1.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .param("page", "999")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    // -------------------------------------------------------------------------
    // Edge case: Exact account balance transfer (boundary value)
    // -------------------------------------------------------------------------
    @Test
    void allowsTransferOfExactAccountBalance() throws Exception {
        Fixture fixture = fixture("1000.00");

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "exact-balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "1000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertEquals(new BigDecimal("0.00"),
                accountRepository.findById(fixture.source().getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("1100.00"),
                accountRepository.findById(fixture.destination().getId()).orElseThrow().getBalance());
    }

    // -------------------------------------------------------------------------
    // Edge case: One cent over balance (boundary value, expected failure)
    // -------------------------------------------------------------------------
    @Test
    void rejectsTransferOnecentOverBalance() throws Exception {
        Fixture fixture = fixture("999.99");

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "one-cent-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "1000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));

        assertEquals(new BigDecimal("999.99"),
                accountRepository.findById(fixture.source().getId()).orElseThrow().getBalance());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
    }

    // -------------------------------------------------------------------------
    // Edge case: Daily limit exact match (boundary value)
    // -------------------------------------------------------------------------
    @Test
    void allowsTransferThatExactlyReachesDailyLimit() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "daily-exact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "60000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // Reject request that exceeds the daily limit by 0.01 TRY
        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "daily-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "0.01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DAILY_LIMIT_EXCEEDED"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Unknown transferId returns 404
    // -------------------------------------------------------------------------
    @Test
    void returnsNotFoundForUnknownTransferId() throws Exception {
        customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));

        mockMvc.perform(get("/api/v1/transfers/" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // Edge case: Isolation check - cannot view another customer's transfer
    // -------------------------------------------------------------------------
    @Test
    void preventsAccessToAnotherCustomersTransferDetail() throws Exception {
        Fixture fixture = fixture();
        String response = mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("Idempotency-Key", "isolation-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(fixture.source().getId(), RECEIVER_IBAN, "5.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String transferId = objectMapper.readTree(response).path("data").path("id").asText();

        // Charlie should not be able to access Alice's transfer
        customerRepository.saveAndFlush(new Customer("oidc-subject-charlie"));
        mockMvc.perform(get("/api/v1/transfers/" + transferId)
                        .with(jwt().jwt(token -> token.subject("oidc-subject-charlie"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void customerLockSerializesConcurrentDailyLimitChecks() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> attemptConcurrentTransfer(
                    ready, start, fixture.source().getId(), "limit-race-1", "40000.00"));
            Future<String> second = executor.submit(() -> attemptConcurrentTransfer(
                    ready, start, fixture.source().getId(), "limit-race-2", "30000.00"));

            ready.await();
            start.countDown();

            String firstResult = first.get();
            String secondResult = second.get();
            assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter("COMPLETED"::equals)
                    .count());
            assertEquals(1, java.util.stream.Stream.of(firstResult, secondResult)
                    .filter("DAILY_LIMIT_EXCEEDED"::equals)
                    .count());
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class));
            assertEquals(2, jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private String attemptConcurrentTransfer(
            CountDownLatch ready,
            CountDownLatch start,
            UUID sourceAccountId,
            String idempotencyKey,
            String amount) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            transferService.create(
                    ALICE_SUBJECT,
                    idempotencyKey,
                    new CreateTransferRequest(sourceAccountId, RECEIVER_IBAN, new BigDecimal(amount), null));
            return "COMPLETED";
        } catch (TransferRuleException exception) {
            return exception.getCode();
        }
    }

    private Fixture fixture() {
        return fixture("100000.00");
    }

    private Fixture fixture(String sourceBalance) {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account source = accountRepository.saveAndFlush(account(alice, SENDER_IBAN, sourceBalance));
        Account destination = accountRepository.saveAndFlush(account(bob, RECEIVER_IBAN, "100.00"));
        return new Fixture(alice, bob, source, destination);
    }

    private Account account(Customer customer, String iban, String balance) {
        return new Account(
                customer,
                iban,
                "TRY",
                AccountStatus.ACTIVE,
                new BigDecimal(balance));
    }

    private String request(UUID sourceAccountId, String receiverIban, String amount) {
        return """
                {
                  "sourceAccountId": "%s",
                  "receiverIban": "%s",
                  "amount": "%s"
                }
                """.formatted(sourceAccountId, receiverIban, amount);
    }

    private record Fixture(Customer alice, Customer bob, Account source, Account destination) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
