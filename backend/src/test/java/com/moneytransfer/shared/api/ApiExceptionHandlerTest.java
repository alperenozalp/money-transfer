package com.moneytransfer.shared.api;

import com.moneytransfer.shared.filter.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void unexpectedExceptionsReturnTheStandardSafeErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test/unexpected").header("X-Trace-Id", "unexpected-test"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.traceId").value("unexpected-test"));
    }

    @Test
    void responseStatusExceptionsDoNotExposeTheirReason() throws Exception {
        mockMvc.perform(get("/test/not-found").header("X-Trace-Id", "response-status-test"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.error.message").value("The request could not be completed."))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal detail"))))
                .andExpect(jsonPath("$.error.traceId").value("response-status-test"));
    }

    @RestController
    private static class FailureController {

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("internal detail: database credentials");
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal detail: private resource");
        }
    }
}
