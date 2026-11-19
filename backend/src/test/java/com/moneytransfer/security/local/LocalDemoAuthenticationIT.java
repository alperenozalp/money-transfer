package com.moneytransfer.security.local;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers(disabledWithoutDocker = true)
class LocalDemoAuthenticationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void issuesCustomerScopedSessionsForThreeDemoUsersAndProtectsCustomerEndpoints() throws Exception {
        String response = mockMvc.perform(post("/api/v1/demo/session")
                        .header("X-Trace-Id", "demo-session-test")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"demo-customer\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "demo-session-test"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.customerId").value("demo-customer"))
                .andExpect(jsonPath("$.data.customerName").value("Demo Kullanıcısı 1"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("data")
                .path("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].balance").exists());

        assertCustomerCanOnlySeeOwnAccounts("demo-recipient", "transfer1234", 1);
        assertCustomerCanOnlySeeOwnAccounts("demo-customer-3", "money1234", 1);

        mockMvc.perform(post("/api/v1/demo/session")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"demo-customer\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        org.junit.jupiter.api.Assertions.assertEquals(3,
                jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(3,
                jdbcTemplate.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    private void assertCustomerCanOnlySeeOwnAccounts(String customerId, String password, int expectedAccounts)
            throws Exception {
        String response = mockMvc.perform(post("/api/v1/demo/session")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response)
                .path("data")
                .path("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(expectedAccounts));
    }
}
