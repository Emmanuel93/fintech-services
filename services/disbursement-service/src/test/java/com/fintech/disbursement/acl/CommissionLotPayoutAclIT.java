package com.fintech.disbursement.acl;

import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.CommissionLotPayoutListener;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.CommissionLotPayoutPayload;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lot of commissions is paid by the SPEI rail like a disposition: commission asks for it, the order pays the net to
 * the beneficiary's CLABE, out of the operation's account (the {@code commission} wildcard of the company mappings).
 */
@SpringBootTest
@ActiveProfiles("test")
class CommissionLotPayoutAclIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("disbursement"); }

    @Override protected List<String> preserve() { return List.of("company_mappings"); }

    @Autowired CommissionLotPayoutListener listener;
    @Autowired DisbursementOrderRepository orders;

    private static CommissionLotPayoutPayload lot(UUID batchId, int attempt, String net) {
        return new CommissionLotPayoutPayload(batchId, "LOTE:" + batchId, attempt, UUID.randomUUID(), "DISTRIBUTOR_LINE",
                "202607", net == null ? null : new BigDecimal(net), "JUANA PEREZ GOMEZ", "646180157000000004",
                "PEGJ800101AB1", "Comisiones 202607 DISTRIBUTOR_LINE", Instant.now());
    }

    @Test
    @DisplayName("the lot's order pays the net to the beneficiary's CLABE, named by its item and keyed by its attempt")
    void ordersTheNet() {
        UUID batch = UUID.randomUUID();

        listener.onLotPayoutRequested(lot(batch, 1, "572.00"));

        var order = orders.findBySourceKey("commission", DisbursementSource.COMMISSION.name(), batch + ":1");
        assertThat(order).isPresent();
        assertThat(order.get().getAmount()).isEqualByComparingTo("572.00");
        assertThat(order.get().getBeneficiary().getAccount()).isEqualTo("646180157000000004");
        assertThat(order.get().getBeneficiary().getName()).isEqualTo("JUANA PEREZ GOMEZ");
        assertThat(order.get().status()).isIn(DisbursementStatus.REQUESTED);
        assertThat(jdbcTemplate.queryForObject("SELECT source_reference FROM disbursement.disbursement_orders "
                + "WHERE source_event_id = ?", String.class, batch + ":1")).isEqualTo("LOTE:" + batch);
        assertThat(jdbcTemplate.queryForObject("SELECT source_metadata->>'batchId' FROM disbursement.disbursement_orders "
                + "WHERE source_event_id = ?", String.class, batch + ":1")).isEqualTo(batch.toString());
    }

    @Test
    @DisplayName("a redelivered attempt is the same order; the next attempt of a failed lot is a new one")
    void onePerAttempt() {
        UUID batch = UUID.randomUUID();

        listener.onLotPayoutRequested(lot(batch, 1, "572.00"));
        listener.onLotPayoutRequested(lot(batch, 1, "572.00"));
        listener.onLotPayoutRequested(lot(batch, 2, "572.00"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM disbursement.disbursement_orders "
                + "WHERE source_reference = ?", Integer.class, "LOTE:" + batch)).isEqualTo(2);
    }

    @Test
    @DisplayName("a lot with nothing to pay orders nothing")
    void nothingToPay() {
        UUID batch = UUID.randomUUID();

        listener.onLotPayoutRequested(lot(batch, 1, null));
        listener.onLotPayoutRequested(lot(batch, 2, "0.00"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM disbursement.disbursement_orders "
                + "WHERE source_reference = ?", Integer.class, "LOTE:" + batch)).isZero();
    }
}
