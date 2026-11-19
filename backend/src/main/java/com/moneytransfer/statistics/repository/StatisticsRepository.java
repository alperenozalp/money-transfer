package com.moneytransfer.statistics.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatisticsRepository {

    private static final String MONTHLY_TOTALS_SQL = """
            SELECT date_trunc('month', t.created_at AT TIME ZONE ?)::date AS month_start,
                   COUNT(*) AS completed_transfer_count,
                   COALESCE(SUM(t.amount), 0) AS completed_outgoing_amount
            FROM transfers t
            JOIN customers c ON c.id = t.sender_customer_id
            WHERE c.identity_subject = ?
              AND t.status = 'COMPLETED'
              AND t.created_at >= ?
              AND t.created_at < ?
            GROUP BY month_start
            """;

    private final JdbcTemplate jdbcTemplate;

    public StatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MonthTotal> monthlyTotals(
            String identitySubject,
            ZoneId zone,
            Instant periodStart,
            Instant periodEnd) {
        return jdbcTemplate.query(
                MONTHLY_TOTALS_SQL,
                (resultSet, rowNumber) -> new MonthTotal(
                        resultSet.getDate("month_start").toLocalDate(),
                        resultSet.getLong("completed_transfer_count"),
                        resultSet.getBigDecimal("completed_outgoing_amount")),
                zone.getId(),
                identitySubject,
                OffsetDateTime.ofInstant(periodStart, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(periodEnd, ZoneOffset.UTC));
    }

    public record MonthTotal(LocalDate monthStart, long completedTransferCount, BigDecimal completedOutgoingAmount) {
    }
}
