package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.CustomerBalanceLedger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Repository
class JdbcCustomerBalanceLedger implements CustomerBalanceLedger {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCustomerBalanceLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID movementId, UUID partyId, String movementType, BigDecimal amount, UUID inboundCreditId,
                       UUID paymentOrderId, UUID creditAccountId, LocalDate businessDate) {
        jdbc.update("""
                INSERT INTO accounting.customer_balance_movements (movement_id, party_id, movement_type, amount,
                       inbound_credit_id, payment_order_id, credit_account_id, business_date, period)
                VALUES (:id, :party, :type, :amount, :credit, :order, :account, :day, :period)
                ON CONFLICT (movement_id) DO NOTHING""",
                new MapSqlParameterSource("id", movementId).addValue("party", partyId).addValue("type", movementType)
                        .addValue("amount", amount).addValue("credit", inboundCreditId).addValue("order", paymentOrderId)
                        .addValue("account", creditAccountId).addValue("day", businessDate)
                        .addValue("period", businessDate.format(PERIOD)));
    }
}
