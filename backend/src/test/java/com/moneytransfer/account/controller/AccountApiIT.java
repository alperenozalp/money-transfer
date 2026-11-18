package com.moneytransfer.account.controller;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.UUID;

import com.moneytransfer.account.model.Account;
import com.moneytransfer.account.model.AccountStatus;
import com.moneytransfer.account.repository.AccountRepository;
import com.moneytransfer.customer.model.Customer;
import com.moneytransfer.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountApiIT.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AccountApiIT {

    private static final Instant NOW = Instant.parse("2026-11-18T23:30:00Z");
    private static final String ALICE_SUBJECT = "oidc-subject-alice";
    private static final String BOB_SUBJECT = "oidc-subject-bob";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void accountsAreScopedToTheVerifiedJwtSubject() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        Account aliceAccount = accountRepository.saveAndFlush(account(
                alice, "TR0000000000000000000000001", "1234.50"));
        accountRepository.saveAndFlush(account(bob, "TR0000000000000000000000002", "9999.99"));

        mockMvc.perform(get("/api/v1/accounts")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT)))
                        .header("X-Trace-Id", "account-test-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "account-test-1"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(aliceAccount.getId().toString()))
                .andExpect(jsonPath("$.data[0].balance").value("1234.50"))
                .andExpect(jsonPath("$.data[0].currency").value("TRY"))
                .andExpect(jsonPath("$.meta").isEmpty());
    }

    @Test
    void limitsCountOnlyTheCustomersCompletedOutgoingTransfersInIstanbulPeriods() throws Exception {
        Customer alice = customerRepository.saveAndFlush(new Customer(ALICE_SUBJECT));
        Customer bob = customerRepository.saveAndFlush(new Customer(BOB_SUBJECT));
        insertTransfer(alice, "250.00", NOW.minusSeconds(30), "COMPLETED");
        insertTransfer(alice, "1000.00", NOW.minusSeconds(2 * 24 * 60 * 60), "COMPLETED");
        insertTransfer(alice, "700.00", Instant.parse("2026-10-31T20:59:59Z"), "COMPLETED");
        insertTransfer(bob, "9000.00", NOW.minusSeconds(30), "COMPLETED");

        mockMvc.perform(get("/api/v1/accounts/transfer-limits")
                        .with(jwt().jwt(token -> token.subject(ALICE_SUBJECT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("TRY"))
                .andExpect(jsonPath("$.data.perTransferMaximum").value("50000.00"))
                .andExpect(jsonPath("$.data.dailyRemaining").value("99750.00"))
                .andExpect(jsonPath("$.data.monthlyRemaining").value("498750.00"));
    }

    @Test
    void unauthenticatedRequestsReceiveTheStandardErrorAndTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header("X-Trace-Id", "account-auth-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Trace-Id", "account-auth-test"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.traceId").value("account-auth-test"));
    }

    @Test
    void unprovisionedCustomersReceiveAForbiddenError() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .with(jwt().jwt(token -> token.subject("unknown-subject")))
                        .header("X-Trace-Id", "unprovisioned-test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CUSTOMER_NOT_PROVISIONED"))
                .andExpect(jsonPath("$.error.traceId").value("unprovisioned-test"));
    }

    @Test
    void unsafeTraceIdsAreReplaced() throws Exception {
        String responseTraceId = mockMvc.perform(get("/api/v1/accounts")
                        .header("X-Trace-Id", "unsafe trace id"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");

        assertNotEquals("unsafe trace id", responseTraceId);
        assertTrue(responseTraceId.length() <= 64);
    }

    private Account account(Customer customer, String iban, String balance) {
        return new Account(
                customer,
                iban,
                "TRY",
                AccountStatus.ACTIVE,
                new BigDecimal(balance));
    }

    private void insertTransfer(Customer sender, String amount, Instant createdAt, String status) {
        jdbcTemplate.update(
                "INSERT INTO transfers (id, sender_customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                sender.getId(),
                new BigDecimal(amount),
                status,
                Timestamp.from(createdAt));
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
