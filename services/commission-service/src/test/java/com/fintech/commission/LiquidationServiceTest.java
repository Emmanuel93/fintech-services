package com.fintech.commission;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.application.service.LiquidationService;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.LiquidationBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LiquidationServiceTest {

    @Mock CommissionRecordRepository recordRepository;
    @Mock LiquidationBatchRepository batchRepository;
    @Mock CommissionEventPublisher eventPublisher;

    LiquidationService service;
    final CommissionProperties properties = new CommissionProperties(); // min = 500

    private final UUID beneficiaryA = UUID.randomUUID();
    private final UUID beneficiaryB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LiquidationService(recordRepository, batchRepository, eventPublisher, properties);
    }

    private CommissionRecord record(UUID beneficiary, String amount) {
        return CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE, UUID.randomUUID(),
                beneficiary, "evt-" + UUID.randomUUID(), new BigDecimal(amount).divide(new BigDecimal("0.30"), 4, java.math.RoundingMode.HALF_UP),
                new BigDecimal("0.30"));
    }

    @Test
    void groupsRecordsByBeneficiary_createsOneBatchEach_aboveMinimum() {
        CommissionRecord a1 = record(beneficiaryA, "300"); // 300 >= min? no, 300 < 500 alone
        CommissionRecord a2 = record(beneficiaryA, "300"); // together 600 >= 500
        CommissionRecord b1 = record(beneficiaryB, "1000");
        given(recordRepository.findByStatusAndPeriod(CommissionRecordStatus.ACCRUED, "202607"))
                .willReturn(List.of(a1, a2, b1));
        given(batchRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        int batches = service.runLiquidation("202607");

        assertThat(batches).isEqualTo(2);
        ArgumentCaptor<LiquidationBatch> captor = ArgumentCaptor.forClass(LiquidationBatch.class);
        then(batchRepository).should(times(2)).save(captor.capture());
        assertThat(a1.getStatus()).isEqualTo(CommissionRecordStatus.LIQUIDATED);
        assertThat(a2.getStatus()).isEqualTo(CommissionRecordStatus.LIQUIDATED);
        assertThat(b1.getStatus()).isEqualTo(CommissionRecordStatus.LIQUIDATED);
        then(eventPublisher).should(times(2)).publishCommissionLiquidated(any());
    }

    @Test
    void belowMinimum_isSkipped_notLiquidated() {
        CommissionRecord tiny = record(beneficiaryA, "100");
        given(recordRepository.findByStatusAndPeriod(CommissionRecordStatus.ACCRUED, "202607"))
                .willReturn(List.of(tiny));

        int batches = service.runLiquidation("202607");

        assertThat(batches).isZero();
        assertThat(tiny.getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
        then(batchRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void noAccruedRecords_noOp() {
        given(recordRepository.findByStatusAndPeriod(CommissionRecordStatus.ACCRUED, "202607"))
                .willReturn(List.of());

        int batches = service.runLiquidation("202607");

        assertThat(batches).isZero();
    }
}
