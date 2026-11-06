package com.fintech.commission;

import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.application.service.LotPayoutOutcomeService;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.commission.domain.LiquidationBatchStatus;
import com.fintech.commission.domain.LotTaxes;
import com.fintech.commission.domain.TaxRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/** What became of a lot's SPEI: settled, it is paid; failed or returned, it waits for the next run and accounting is told. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LotPayoutOutcomeServiceTest {

    @Mock LiquidationBatchRepository batches;
    @Mock CommissionEventPublisher publisher;

    private LotPayoutOutcomeService service;
    private LiquidationBatch lot;

    @BeforeEach
    void setUp() {
        service = new LotPayoutOutcomeService(batches, publisher);
        BeneficiaryProfile company = BeneficiaryProfile.of(UUID.randomUUID());
        company.updateFiscal("DIS010101AB1", "BUSINESS", "Distribuidora", "601");
        lot = LiquidationBatch.open(company.getPartyId(), "DISTRIBUTOR_LINE", "202604",
                LotTaxes.of(new BigDecimal("600.00"), new BigDecimal("96.00"), company, TaxRules.MEXICO), 3);
        lot.requestPayout();
        given(batches.findById(lot.getBatchId())).willReturn(Optional.of(lot));
        given(batches.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("settled: the lot is paid with the order's tracking key; a second report changes nothing")
    void paid() {
        Instant at = Instant.parse("2026-05-05T17:00:00Z");
        service.onPaid(lot.getBatchId(), "STP123", at);
        service.onPaid(lot.getBatchId(), "STP999", at.plusSeconds(60));

        assertThat(lot.getStatus()).isEqualTo(LiquidationBatchStatus.CONFIRMED);
        assertThat(lot.getPaymentRef()).isEqualTo("STP123");
        assertThat(lot.getProcessedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("failed: the lot waits for the next run with the reason, and accounting is told")
    void failed() {
        service.onFailed(lot.getBatchId(), 1, "rechazado: cuenta inexistente");

        assertThat(lot.getStatus()).isEqualTo(LiquidationBatchStatus.PENDING);
        assertThat(lot.getFailureReason()).isEqualTo("rechazado: cuenta inexistente");
        then(publisher).should().publishLotPayoutFailed(lot, 1, "rechazado: cuenta inexistente");
    }

    @Test
    @DisplayName("the failure of an earlier attempt does not undo the current one")
    void staleFailure() {
        service.onFailed(lot.getBatchId(), 1, "rechazado");
        lot.requestPayout();   // attempt 2 in flight

        service.onFailed(lot.getBatchId(), 1, "rechazado otra vez");

        assertThat(lot.getStatus()).isEqualTo(LiquidationBatchStatus.SENT);
        then(publisher).should().publishLotPayoutFailed(lot, 1, "rechazado");
        then(publisher).should(never()).publishLotPayoutFailed(lot, 1, "rechazado otra vez");
    }

    @Test
    @DisplayName("an outcome of a lot this service does not have changes nothing")
    void unknownLot() {
        UUID other = UUID.randomUUID();
        given(batches.findById(other)).willReturn(Optional.empty());

        service.onFailed(other, 1, "rechazado");
        service.onPaid(other, "STP1", Instant.now());

        then(publisher).should(never()).publishLotPayoutFailed(any(), anyInt(), anyString());
    }
}
