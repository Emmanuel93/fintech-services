package com.fintech.charges.application;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.application.service.CancellationCompensationService;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeStatus;
import com.fintech.charges.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A credit cancelled because its only disbursement never happened (D63) keeps none of its charges: each one still
 * applied is reversed (the opening fee and its IVA, interest accrued meanwhile) and its accrual closes; one reversed
 * already is left alone, so a redelivery does nothing. Plus how long a large account takes.
 */
class CancellationCompensationServiceTest {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OBLIGOR = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    private ChargeRecordRepository charges;
    private AccrualScheduleRepository schedules;
    private ChargeEventPublisher publisher;
    private CancellationCompensationService compensation;
    private AccrualSchedule schedule;

    @BeforeEach
    void setUp() {
        charges = mock(ChargeRecordRepository.class);
        schedules = mock(AccrualScheduleRepository.class);
        publisher = mock(ChargeEventPublisher.class);
        compensation = new CancellationCompensationService(charges, schedules, publisher);
        schedule = AccrualSchedule.create(ACCOUNT, OBLIGOR, "PERSONAL_LOAN", "INSTALLMENT", new BigDecimal("0.24"),
                new BigDecimal("0.36"), new BigDecimal("50000"), new BigDecimal("50000"), 0);
        given(schedules.findByCreditAccountId(ACCOUNT)).willReturn(Optional.of(schedule));
    }

    private static ChargeRecord fee() {
        return ChargeRecord.create(ACCOUNT, OBLIGOR, ChargeType.OPENING_FEE, new BigDecimal("50000"),
                new BigDecimal("0.03"), null, new BigDecimal("1500.00"), BigDecimal.ZERO, DAY, null);
    }

    @Test
    @DisplayName("cada cargo vivo del crédito cancelado se reversa una vez, su devengo se cierra y repetir no hace nada")
    void everyLiveChargeIsReversedOnce() {
        ChargeRecord fee = fee();
        ChargeRecord iva = ChargeRecord.createIva(ACCOUNT, OBLIGOR, new BigDecimal("240.00"), DAY, fee.getChargeId());
        ChargeRecord interest = ChargeRecord.create(ACCOUNT, OBLIGOR, ChargeType.ORDINARY_INTEREST,
                new BigDecimal("50000"), new BigDecimal("0.24"), 1, new BigDecimal("33.33"), BigDecimal.ZERO,
                DAY.plusDays(1), null);
        ChargeRecord waived = fee();
        waived.waive("operador");
        given(charges.findAllByCreditAccountIdOrderByAccrualDateDesc(ACCOUNT))
                .willReturn(List.of(interest, iva, fee, waived));

        int reversed = compensation.onCreditAccountCancelled(ACCOUNT, "DISBURSEMENT_FAILED");

        assertThat(reversed).isEqualTo(3);
        assertThat(List.of(fee, iva, interest)).allMatch(c -> c.getStatus().equals(ChargeStatus.REVERSED.name()));
        assertThat(waived.getStatus()).isEqualTo(ChargeStatus.WAIVED.name());
        verify(publisher).publishChargeReversed(eq(fee.getChargeId().toString()), eq(ACCOUNT), eq("OPENING_FEE"),
                any(), eq(false));
        verify(publisher).publishChargeReversed(eq(iva.getChargeId().toString()), eq(ACCOUNT), eq("IVA"),
                any(), eq(false));
        assertThat(schedule.getStatus()).isEqualTo("CLOSED");

        assertThat(compensation.onCreditAccountCancelled(ACCOUNT, "DISBURSEMENT_FAILED")).as("redelivery").isZero();
        verify(publisher, times(3)).publishChargeReversed(anyString(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("rendimiento: veinte mil cargos de un crédito se reversan en menos de un segundo")
    void performance() {
        List<ChargeRecord> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(ChargeRecord.create(ACCOUNT, OBLIGOR, ChargeType.ORDINARY_INTEREST, new BigDecimal("50000"),
                    new BigDecimal("0.24"), 1, new BigDecimal("33.33"), BigDecimal.ZERO, DAY.plusDays(i % 365), null));
        }
        given(charges.findAllByCreditAccountIdOrderByAccrualDateDesc(ACCOUNT)).willReturn(many);

        long started = System.nanoTime();
        int reversed = compensation.onCreditAccountCancelled(ACCOUNT, "DISBURSEMENT_FAILED");
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(reversed).isEqualTo(20_000);
        assertThat(ms).as("20,000 cargos en %d ms", ms).isLessThan(1_000);
    }
}
