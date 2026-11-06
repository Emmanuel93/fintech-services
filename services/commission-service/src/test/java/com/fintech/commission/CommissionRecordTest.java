package com.fintech.commission;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.CommissionSource;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.InvalidCommissionRecordStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The commission ledger entry: a share of the interest a payment collected, with its IVA, in the period of the
 * payment's portfolio day; and the reversal a returned payment appends, in proportion to the interest it restores.
 */
class CommissionRecordTest {

    private static final BigDecimal IVA = new BigDecimal("0.16");
    private final UUID loan = UUID.randomUUID();
    private final UUID distributor = UUID.randomUUID();

    private CommissionRecord accrue(String basis, String rate, LocalDate day) {
        return CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(loan, distributor, "DISTRIBUTOR_LINE", "evt-" + UUID.randomUUID(), "pago-1", day),
                new BigDecimal(basis), new BigDecimal(rate), IVA);
    }

    private static CommissionRecord.Reversed after(CommissionRecord... reversals) {
        CommissionRecord.Reversed r = CommissionRecord.Reversed.NONE;
        for (CommissionRecord x : reversals) r = r.plus(x);
        return r;
    }

    @Test
    @DisplayName("CR-01: commission = basis × rate in centavos, and its IVA on top")
    void accruesTheShareAndItsIva() {
        CommissionRecord r = accrue("1269.24", "0.10", LocalDate.of(2026, 3, 31));

        assertThat(r.getAmount()).isEqualByComparingTo("126.92");
        assertThat(r.getIvaAmount()).isEqualByComparingTo("20.31");
        assertThat(r.gross()).isEqualByComparingTo("147.23");
        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
        assertThat(r.getPaymentRef()).isEqualTo("pago-1");
        assertThat(r.getProductType()).isEqualTo("DISTRIBUTOR_LINE");
    }

    @Test
    @DisplayName("the period is the payment's portfolio day's: a payment of 31 March read on 1 April is March's")
    void periodOfThePaymentsDay() {
        assertThat(accrue("100", "0.30", LocalDate.of(2026, 3, 31)).getPeriod()).isEqualTo("202603");
        assertThat(accrue("100", "0.30", LocalDate.of(2026, 3, 31)).getValueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("a return that restores all the interest takes the whole commission back, as a negative record")
    void fullReversal() {
        CommissionRecord original = accrue("1000", "0.30", LocalDate.of(2026, 4, 10));

        CommissionRecord reversal = CommissionRecord.reversalOf(original, new BigDecimal("1000"),
                CommissionRecord.Reversed.NONE, "evt-ret", LocalDate.of(2026, 4, 12));

        assertThat(reversal.getAmount()).isEqualByComparingTo("-300.00");
        assertThat(reversal.getIvaAmount()).isEqualByComparingTo("-48.00");
        assertThat(reversal.getBasis()).isEqualByComparingTo("-1000");
        assertThat(reversal.getReversesCommissionId()).isEqualTo(original.getCommissionId());
        assertThat(reversal.isReversal()).isTrue();
        assertThat(reversal.getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
        // The original is not edited: the ledger only grows.
        assertThat(original.getAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("a partial return takes back its share; two returns of the same payment leave no centavo behind")
    void partialReversals() {
        CommissionRecord original = accrue("1000.00", "0.333", LocalDate.of(2026, 4, 10));   // 333.00, IVA 53.28

        CommissionRecord first = CommissionRecord.reversalOf(original, new BigDecimal("333.33"),
                CommissionRecord.Reversed.NONE, "evt-ret-1", LocalDate.of(2026, 4, 12));
        CommissionRecord second = CommissionRecord.reversalOf(original, new BigDecimal("666.67"),
                after(first), "evt-ret-2", LocalDate.of(2026, 4, 13));

        assertThat(first.getAmount()).isEqualByComparingTo("-111.00");
        assertThat(original.getAmount().add(first.getAmount()).add(second.getAmount())).isEqualByComparingTo("0");
        assertThat(original.getIvaAmount().add(first.getIvaAmount()).add(second.getIvaAmount())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a return never takes back more than is left; once it was all taken, nothing")
    void cappedAndExhausted() {
        CommissionRecord original = accrue("500", "0.30", LocalDate.of(2026, 4, 10));

        CommissionRecord all = CommissionRecord.reversalOf(original, new BigDecimal("900"),
                CommissionRecord.Reversed.NONE, "evt-ret-1", LocalDate.of(2026, 4, 12));
        CommissionRecord nothing = CommissionRecord.reversalOf(original, new BigDecimal("100"),
                after(all), "evt-ret-2", LocalDate.of(2026, 4, 13));

        assertThat(all.getAmount()).isEqualByComparingTo("-150.00");
        assertThat(nothing).isNull();
    }

    @Test
    @DisplayName("only an accrued record goes into a lot")
    void markLiquidatedOnlyFromAccrued() {
        CommissionRecord r = accrue("1000", "0.30", LocalDate.of(2026, 4, 10));
        UUID lot = UUID.randomUUID();
        r.markLiquidated(lot);

        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.LIQUIDATED);
        assertThat(r.getLiquidationBatchId()).isEqualTo(lot);
        assertThatThrownBy(() -> r.markLiquidated(UUID.randomUUID()))
                .isInstanceOf(InvalidCommissionRecordStateException.class);
    }
}
