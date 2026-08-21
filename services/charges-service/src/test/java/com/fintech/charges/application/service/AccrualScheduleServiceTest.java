package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.AccrualScheduleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccrualScheduleServiceTest {

    @Mock AccrualScheduleRepository scheduleRepo;
    @Mock ChargeRecordRepository chargeRecordRepo;
    @Mock ChargeEventPublisher eventPublisher;
    @Mock AccountBalanceSnapshotRepository snapshotRepo;

    AccrualScheduleService service;

    @BeforeEach
    void setUp() {
        ChargesProperties props = new ChargesProperties();
        props.setVatRate(new BigDecimal("0.16"));
        props.setGracePeriodDays(3);
        props.setMoratoriumRateMultiplier(new BigDecimal("1.5"));
        props.setOpeningFeeRate(BigDecimal.ZERO);
        service = new AccrualScheduleService(scheduleRepo, chargeRecordRepo, eventPublisher, props, snapshotRepo);
    }

    @Test
    void createFromActivation_createsSchedule() {
        UUID accountId     = UUID.randomUUID();
        UUID obligorId     = UUID.randomUUID();
        BigDecimal nominal = new BigDecimal("0.24");
        BigDecimal mora    = new BigDecimal("0.36");
        BigDecimal balance = new BigDecimal("10000.00");

        when(scheduleRepo.existsByCreditAccountId(accountId)).thenReturn(false);
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createFromActivation(accountId, obligorId, "PERSONAL_LOAN", "AMORTIZING",
                nominal, mora, balance, BigDecimal.ZERO);

        ArgumentCaptor<AccrualSchedule> cap = ArgumentCaptor.forClass(AccrualSchedule.class);
        verify(scheduleRepo).save(cap.capture());

        AccrualSchedule saved = cap.getValue();
        assertThat(saved.getCreditAccountId()).isEqualTo(accountId);
        assertThat(saved.getNominalRate()).isEqualByComparingTo(nominal);
        assertThat(saved.getMoratoriumRate()).isEqualByComparingTo(mora);
        assertThat(saved.getStatus()).isEqualTo(AccrualScheduleStatus.ACTIVE.name());
    }

    @Test
    void createFromActivation_isIdempotent() {
        UUID accountId = UUID.randomUUID();
        when(scheduleRepo.existsByCreditAccountId(accountId)).thenReturn(true);

        service.createFromActivation(accountId, UUID.randomUUID(), "CREDIT_CARD", "REVOLVING",
                new BigDecimal("0.30"), null, new BigDecimal("5000"), BigDecimal.ZERO);

        verify(scheduleRepo, never()).save(any());
    }

    @Test
    void createFromActivation_fallsBackToMoratoriumMultiplier_whenMoraRateIsZero() {
        UUID accountId     = UUID.randomUUID();
        BigDecimal nominal = new BigDecimal("0.24");

        when(scheduleRepo.existsByCreditAccountId(accountId)).thenReturn(false);
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createFromActivation(accountId, UUID.randomUUID(), "PERSONAL_LOAN", "AMORTIZING",
                nominal, BigDecimal.ZERO, new BigDecimal("10000"), BigDecimal.ZERO);

        ArgumentCaptor<AccrualSchedule> cap = ArgumentCaptor.forClass(AccrualSchedule.class);
        verify(scheduleRepo).save(cap.capture());
        // Expected: 0.24 * 1.5 = 0.36
        assertThat(cap.getValue().getMoratoriumRate()).isEqualByComparingTo(new BigDecimal("0.36000000"));
    }

    @Test
    void createFromActivation_chargesOpeningFee_whenRatePositive() {
        UUID accountId = UUID.randomUUID();

        when(scheduleRepo.existsByCreditAccountId(accountId)).thenReturn(false);
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chargeRecordRepo.existsByChargeTypeAndCreditAccountId(anyString(), eq(accountId))).thenReturn(false);
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createFromActivation(accountId, UUID.randomUUID(), "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("10000"), new BigDecimal("0.02"));

        // base charge + IVA = 2 saves
        verify(chargeRecordRepo, times(2)).save(any());
        // 2 events: OPENING_FEE + IVA
        verify(eventPublisher, times(2)).publishChargeApplied(any(), eq(accountId), any(), any());
    }

    @Test
    void updateBalanceFromEvent_updatesBalance() {
        UUID accountId = UUID.randomUUID();
        AccrualSchedule existing = AccrualSchedule.create(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("10000"), new BigDecimal("10000"), 3);

        when(scheduleRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(existing));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateBalanceFromEvent(accountId, new BigDecimal("8000.00"), "ACTIVE");

        assertThat(existing.getPrincipalBalance()).isEqualByComparingTo(new BigDecimal("8000.00"));
        assertThat(existing.getStatus()).isEqualTo(AccrualScheduleStatus.ACTIVE.name());
    }

    @Test
    void updateBalanceFromEvent_closesSchedule_whenSettled() {
        UUID accountId = UUID.randomUUID();
        AccrualSchedule existing = AccrualSchedule.create(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("10000"), new BigDecimal("10000"), 3);

        when(scheduleRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(existing));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateBalanceFromEvent(accountId, BigDecimal.ZERO, "SETTLED");

        assertThat(existing.getStatus()).isEqualTo(AccrualScheduleStatus.CLOSED.name());
    }
}
