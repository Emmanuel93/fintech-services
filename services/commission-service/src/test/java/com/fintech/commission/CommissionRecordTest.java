package com.fintech.commission;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.InvalidCommissionRecordStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommissionRecordTest {

    @Test
    void accrue_computesAmountAsBasisTimesRate() {
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), "evt-1", new BigDecimal("1000"), new BigDecimal("0.30"));

        assertThat(r.getAmount()).isEqualByComparingTo("300.00");
        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
        assertThat(r.isAccrued()).isTrue();
    }

    @Test
    void reverse_transitionsToReversed() {
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), "evt-1", new BigDecimal("1000"), new BigDecimal("0.30"));

        r.reverse();

        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.REVERSED);
    }

    @Test
    void reverse_alreadyReversed_throws() {
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), "evt-1", new BigDecimal("1000"), new BigDecimal("0.30"));
        r.reverse();

        assertThatThrownBy(r::reverse).isInstanceOf(InvalidCommissionRecordStateException.class);
    }

    @Test
    void markLiquidated_requiresAccruedStatus() {
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), "evt-1", new BigDecimal("1000"), new BigDecimal("0.30"));
        r.reverse();

        assertThatThrownBy(() -> r.markLiquidated(UUID.randomUUID()))
                .isInstanceOf(InvalidCommissionRecordStateException.class);
    }

    @Test
    void markLiquidated_setsStatusAndBatchRef() {
        CommissionRecord r = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                UUID.randomUUID(), UUID.randomUUID(), "evt-1", new BigDecimal("1000"), new BigDecimal("0.30"));
        UUID batchId = UUID.randomUUID();

        r.markLiquidated(batchId);

        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.LIQUIDATED);
        assertThat(r.getLiquidationBatchId()).isEqualTo(batchId);
    }
}
