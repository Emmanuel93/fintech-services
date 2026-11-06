package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.AccrualUnitStore.Basis;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeRecord;
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

    static AccrualSchedule buildSchedule(BigDecimal principal, BigDecimal nominal, BigDecimal delinquency) {
        AccrualSchedule s = AccrualSchedule.create(
                UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN", "AMORTIZING",
                nominal, delinquency, principal, principal, 3);
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

    /** El caso real del 31 de octubre: línea de distribuidora con $29 al 20 %. Interés 0.02, IVA 0.0032 → sin IVA. */
    @Test
    void elInteresDeCentavosVaSinIva() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("29.00"), new BigDecimal("0.20"), new BigDecimal("0.36"));
        LocalDate today = LocalDate.of(2026, 10, 31);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueInterestForSchedule(schedule.getScheduleId(), today, true);

        ArgumentCaptor<com.fintech.charges.domain.ChargeRecord> guardados =
                ArgumentCaptor.forClass(com.fintech.charges.domain.ChargeRecord.class);
        verify(chargeRecordRepo, atLeastOnce()).save(guardados.capture());
        assertThat(guardados.getAllValues()).allSatisfy(c -> {
            assertThat(c.getChargeType()).isEqualTo(ChargeType.ORDINARY_INTEREST.name());
            assertThat(c.getDayCloseDate()).isEqualTo(today);
        });
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("ORDINARY_INTEREST"),
                eq(new BigDecimal("0.02")), eq(today), eq(today));
        verify(eventPublisher, never()).publishChargeApplied(any(), any(), eq("IVA"), any(), any(), any());
        assertThat(schedule.needsAccrual(today)).isFalse();
    }

    /** Saldo de $5 al 20 %: el día da 0.0028 → ni interés ni IVA, pero el día queda devengado. */
    @Test
    void elInteresQueNoLlegaAlCentavoNoGeneraCargo() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("5.00"), new BigDecimal("0.20"), new BigDecimal("0.36"));
        LocalDate today = LocalDate.of(2026, 10, 31);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));

        service.accrueInterestForSchedule(schedule.getScheduleId(), today, true);

        verify(chargeRecordRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
        assertThat(schedule.needsAccrual(today)).isFalse();
    }

    /** Vencido de $3 al 36 %: el moratorio del día da 0.003 → sin cargo, el día queda devengado. */
    @Test
    void elMoratorioQueNoLlegaAlCentavoNoGeneraCargo() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("10000.00"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate today = LocalDate.of(2026, 1, 15);
        schedule.updateMoratorium(new BigDecimal("3.00"), LocalDate.of(2026, 1, 5), today, 3);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));

        service.accrueMoratoriumForSchedule(schedule.getScheduleId(), today, true);

        verify(chargeRecordRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
        assertThat(schedule.needsMoratoriumAccrual(today)).isFalse();
    }

    /**
     * BK-19 · el moratorio se cobra sobre el <b>capital vencido</b>.
     *
     * <p>La versión anterior de esta prueba usaba el saldo completo —$10 000— como base y afirmaba
     * $10.00 diarios. Con una sola cuota vencida de $1 000 de capital, lo correcto es $1.00: **diez
     * veces menos**. Que la prueba pasara era el problema, no la garantía.
     */
    @Test
    void elMoratorioSeCobraSobreElCapitalVencidoNoSobreElSaldo() {
        // Saldo del crédito: 10 000. Vencido: 1 000. → 1000 * 0.36 / 360 = 1.00
        BigDecimal principal = new BigDecimal("10000.00");
        AccrualSchedule schedule = buildSchedule(principal, new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate vencio = LocalDate.of(2026, 1, 5);
        schedule.updateMoratorium(new BigDecimal("1000.00"), vencio, LocalDate.of(2026, 1, 15), 3);
        LocalDate today = LocalDate.of(2026, 1, 15);

        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueMoratoriumForSchedule(schedule.getScheduleId(), today);

        verify(eventPublisher).publishChargeApplied(any(), any(), eq("MORATORIUM_INTEREST"),
                eq(new BigDecimal("1.00")), eq(today));
        // IVA: 1.00 * 0.16 = 0.16
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("0.16")), eq(today));
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
    // --- the day close path: the basis comes from the portfolio's snapshot, not from the schedule -----

    @Test
    void accrueInterestOnBasis_usesTheSnapshotPrincipalNotTheLiveBalance() {
        // The schedule holds 10,000 today; at the end of the day the portfolio saw 4,000.
        AccrualSchedule schedule = buildSchedule(new BigDecimal("10000.00"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate day = LocalDate.of(2026, 11, 10);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), day,
                new Basis(new BigDecimal("4000.00"), BigDecimal.ZERO, null));

        // 4,000 × 0.24 / 360 = 2.67, and the charge goes into the day's batch with that basis
        // (saved, tagged with the batch and saved again, then its IVA).
        assertThat(interest).isEqualByComparingTo("2.67");
        ArgumentCaptor<ChargeRecord> saved = ArgumentCaptor.forClass(ChargeRecord.class);
        verify(chargeRecordRepo, times(3)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getBasis()).isEqualByComparingTo("4000.00");
        assertThat(saved.getAllValues().get(0).getDayCloseDate()).isEqualTo(day);
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("ORDINARY_INTEREST"), eq(new BigDecimal("2.67")), eq(day), eq(day));
        assertThat(schedule.getLastAccrualDate()).isEqualTo(day);
    }

    @Test
    void accrueMoratoriumOnBasis_runsFromTheOldestDueDatePlusGraceEvenIfTheLiveFlagIsOff() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("10000.00"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate day = LocalDate.of(2026, 11, 10);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Oldest installment due 3 days ago, grace of 3: the moratorium runs today on 1,800 of overdue principal.
        BigDecimal moratorium = service.accrueMoratoriumOnBasis(schedule.getScheduleId(), day,
                new Basis(new BigDecimal("10000.00"), new BigDecimal("1800.00"), day.minusDays(3)));

        // 1,800 × 0.36 / 360 = 1.80
        assertThat(moratorium).isEqualByComparingTo("1.80");
        assertThat(schedule.isMoratoriumActive()).as("the live flag was never consulted nor changed").isFalse();
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("MORATORIUM_INTEREST"), eq(new BigDecimal("1.80")), eq(day), eq(day));
        assertThat(schedule.getLastMoratoriumAccrualDate()).isEqualTo(day);
    }

    @Test
    void accrueMoratoriumOnBasis_doesNotRunWithinGraceOrWithoutOverduePrincipal() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("10000.00"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate day = LocalDate.of(2026, 11, 10);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));

        // Due 2 days ago with a grace of 3: still within grace.
        assertThat(service.accrueMoratoriumOnBasis(schedule.getScheduleId(), day,
                new Basis(new BigDecimal("10000.00"), new BigDecimal("1800.00"), day.minusDays(2)))).isZero();
        // Nothing overdue at the end of the day, whatever the live flag says.
        schedule.activateMoratorium(day.minusDays(30));
        assertThat(service.accrueMoratoriumOnBasis(schedule.getScheduleId(), day,
                new Basis(new BigDecimal("10000.00"), BigDecimal.ZERO, null))).isZero();

        verifyNoInteractions(chargeRecordRepo, eventPublisher);
    }

    // --- per period: a distributor's placements, each with its own plan --------------------------------

    private static AccrualSchedule perPeriodSchedule() {
        AccrualSchedule s = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(), "DISTRIBUTOR_LINE", "REVOLVING",
                new BigDecimal("0.20"), new BigDecimal("0.30"), new BigDecimal("50000.00"), new BigDecimal("50000.00"), 3);
        s.accrueInterest(AccrualSchedule.PER_PERIOD);
        return s;
    }

    @Test
    void perPeriod_chargesThePlansInterestTheDayThePeriodFallsDue() {
        AccrualSchedule schedule = perPeriodSchedule();
        LocalDate vence = LocalDate.of(2026, 4, 15);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // The placement's first installment: 50,000 × 0.20 / 26 = 384.62, not 14 × 50,000 × 0.20 / 360 = 388.89.
        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), vence,
                new Basis(new BigDecimal("50000.00"), BigDecimal.ZERO, null, new BigDecimal("384.62")));

        assertThat(interest).isEqualByComparingTo("384.62");
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("ORDINARY_INTEREST"), eq(new BigDecimal("384.62")), eq(vence), eq(vence));
        // Its IVA, on the interest charged: 384.62 × 0.16 = 61.54, the plan's tax for the installment.
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("61.54")), eq(vence), eq(vence));
        assertThat(schedule.getLastAccrualDate()).isEqualTo(vence);
    }

    @Test
    void perPeriod_chargesTheIvaEachInstallmentSets_soTheLineOwesWhatItsClientsPay() {
        AccrualSchedule schedule = perPeriodSchedule();
        LocalDate corte = LocalDate.of(2025, 3, 21);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Four placements fall due at the cutoff: interest 125.14 + 42.54 + 363.16 + 32.72 = 563.56, and each client pays
        // its installment's IVA, 20.02 + 6.81 + 58.11 + 5.24 = 90.18. Sixteen percent of the total would be 90.17.
        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), corte,
                new Basis(new BigDecimal("14237.14"), BigDecimal.ZERO, null, new BigDecimal("563.56"), new BigDecimal("90.18")));

        assertThat(interest).isEqualByComparingTo("563.56");
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("90.18")), eq(corte), eq(corte));
    }

    @Test
    void daily_ignoresAScheduledTax_itsIvaIsSixteenPercentOfTheDaysInterest() {
        AccrualSchedule schedule = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(), "REVOLVING_LINE", "REVOLVING",
                new BigDecimal("0.36"), new BigDecimal("0.30"), new BigDecimal("10000.00"), new BigDecimal("10000.00"), 3);
        LocalDate dia = LocalDate.of(2025, 3, 21);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 10,000 × 0.36 / 360 = 10.00 a day and its IVA 1.60, whatever a snapshot says about plans.
        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), dia,
                new Basis(new BigDecimal("10000.00"), BigDecimal.ZERO, null, null, new BigDecimal("99.99")));

        assertThat(interest).isEqualByComparingTo("10.00");
        verify(eventPublisher).publishChargeApplied(any(), any(), eq("IVA"), eq(new BigDecimal("1.60")), eq(dia), eq(dia));
    }

    @Test
    void perPeriod_chargesNothingOnADayNoPeriodFallsDue_andTheDayIsAccrued() {
        AccrualSchedule schedule = perPeriodSchedule();
        LocalDate dia = LocalDate.of(2026, 4, 9);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));

        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), dia,
                new Basis(new BigDecimal("50000.00"), BigDecimal.ZERO, null, BigDecimal.ZERO));

        assertThat(interest).isZero();
        verify(chargeRecordRepo, never()).save(any());
        verify(eventPublisher, never()).publishChargeApplied(any(), any(), any(), any(), any(), any());
        assertThat(schedule.getLastAccrualDate()).isEqualTo(dia);
    }

    @Test
    void daily_ignoresThePlansFigures() {
        AccrualSchedule schedule = buildSchedule(new BigDecimal("4000.00"), new BigDecimal("0.24"), new BigDecimal("0.36"));
        LocalDate day = LocalDate.of(2026, 11, 10);
        when(scheduleRepo.findById(schedule.getScheduleId())).thenReturn(Optional.of(schedule));
        when(chargeRecordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal interest = service.accrueInterestOnBasis(schedule.getScheduleId(), day,
                new Basis(new BigDecimal("4000.00"), BigDecimal.ZERO, null, new BigDecimal("999.00")));

        assertThat(interest).isEqualByComparingTo("2.67");
    }
}
