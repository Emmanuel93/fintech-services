package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.PayoutLinks;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
class JdbcPayoutLinks implements PayoutLinks {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPayoutLinks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void learn(Link link) {
        jdbc.update("""
                INSERT INTO accounting.payout_links (payment_request_id, credit_account_id, item_ref, source_reference, amount)
                VALUES (:order, :account, :item, :reference, :amount)
                ON CONFLICT (payment_request_id) DO NOTHING""",
                new MapSqlParameterSource("order", link.paymentRequestId()).addValue("account", link.creditAccountId())
                        .addValue("item", link.itemRef()).addValue("reference", link.sourceReference())
                        .addValue("amount", link.amount()));
    }
}
