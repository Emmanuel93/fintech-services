package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterestAccrualServiceTest {

    @Mock AccrualScheduleRepository scheduleRepo;
    @Mock ChargeRecordRepository chargeRecordRepo;
    @Mock ChargeEventPublisher eventPublisher;

    InterestAccrualService service;

    static AccrualSchedule buildSchedule(BigDecimal principal, BigDecimal nominal, BigDecimal mora) {
        AccrualSchedule s = AccrualSchedule.create(
                UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN", "AMORTIZING",
                nominal, mora, principal, principal, 3);
        return s;
    }

    @BeforeEach
    void setUp() {
        ChargesProperties props = new ChargesProperties();
        props.setVatRate(new BigDecimal("0.16"));
        props.setGracePeriodDays(3);
        props.setMoratoriumRateMultiplier(new BigDecimal("1.5"));
        props.setOpeningFeeRate(BigDecimal.ZERO);
        service = new InterestAccrualService(scheduleRepo, chargeRecordRepo, eventPublisher, props);
    }

    @Test
    void accrueInterest_calculatesCorrectAmount() {
        // 10000 * 0.24 / 360 = 6.67
        BigDecimal principal = new BigDecimal("10000.00");
        BigDecimal nominal   = new BigDecimal("0.24");
        AccrualSchedule schedule = buildSchedule(principal, nominal, new BigDecimal("0.36"));
        LocalDate today = LocalDate.of(2026, 1, 15);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueInterestForSchedule(schedule.getScheduleId(), today);

        // 2 records: ORDINARY_INTEREST + IVA
        verify(chargeRecordRepo, times(2)).save(any());
        // ORDINARY_INTEREST event → accruedInterestBalance. La fecha de devengo viaja con el cargo:
        // es de donde contabilidad saca el período, así que devengar un día de junio en agosto tiene
        // que seguir asentándose en junio.
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("ORDINARY_INTEREST"),
                eq(new BigDecimal("6.67")), eq(today));
        // IVA event: 6.67 * 0.16 = 1.07
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("1.07")), eq(today));
    }

    @Test
    void accrueInterest_skipsZeroBalance() {
        AccrualSchedule schedule = buildSchedule(BigDecimal.ZERO, new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate today = LocalDate.of(2026, 1, 15);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(scheduleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueInterestForSchedule(schedule.getScheduleId(), today);

        verify(chargeRecordRepo, never()).save(any());
        verify(eventPublisher, never()).publishChargeApplied(any(), any(), any(), any(), any());
    }

    @Test
    void accrueMoratorium_calculatesCorrectAmount() {
        // 10000 * 0.36 / 360 = 10.00
        BigDecimal principal = new BigDecimal("10000.00");
        AccrualSchedule schedule = buildSchedule(principal, new BigDecimal("0.24"), new BigDecimal("0.36"));
        schedule.activateMoratorium(LocalDate.of(2026, 1, 10));
        LocalDate today = LocalDate.of(2026, 1, 15);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueMoratoriumForSchedule(schedule.getScheduleId(), today);

        verify(eventPublisher).publishChargeApplied(any(), any(), eq("MORATORIUM_INTEREST"),
                eq(new BigDecimal("10.00")), eq(today));
        // IVA: 10.00 * 0.16 = 1.60
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("1.60")), eq(today));
    }

    @Test
    void accrueMoratorium_skips_whenNotActive() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("10000"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        // moratorium NOT activated

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));

        service.accrueMoratoriumForSchedule(schedule.getScheduleId(), LocalDate.now());

        verify(chargeRecordRepo, never()).save(any());
    }

    @Test
    void findScheduleIdsForAccrual_returnsSchedulesThatNeedAccrual() {
        AccrualSchedule s1 = buildSchedule(new BigDecimal("5000"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        AccrualSchedule s2 = buildSchedule(new BigDecimal("8000"), new BigDecimal("0.20"), new BigDecimal("0.30"));
        // s2 has already accrued for today
        LocalDate today = LocalDate.now();
        s2.markAccruedFor(today);

        when(scheduleRepo.findAllByStatus("ACTIVE")).thenReturn(List.of(s1, s2));

        List<UUID> ids = service.findScheduleIdsForAccrual(today);

        assertThat(ids).containsExactly(s1.getScheduleId());
    }
}
