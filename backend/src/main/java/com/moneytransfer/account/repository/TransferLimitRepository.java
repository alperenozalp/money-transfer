package com.moneytransfer.account.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferLimitRepository {

    private static final String SUM_COMPLETED_OUTGOING_SQL = """
            SELECT COALESCE(SUM(t.amount), 0)
            FROM transfers t
            JOIN customers c ON c.id = t.sender_customer_id
            WHERE c.identity_subject = ?
              AND t.status = 'COMPLETED'
              AND t.created_at >= ?
              AND t.created_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public TransferLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BigDecimal sumCompletedOutgoing(
            String identitySubject,
            Instant periodStart,
            Instant periodEnd) {
        return jdbcTemplate.queryForObject(
                SUM_COMPLETED_OUTGOING_SQL,
                BigDecimal.class,
                identitySubject,
                OffsetDateTime.ofInstant(periodStart, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(periodEnd, ZoneOffset.UTC));
    }
}
