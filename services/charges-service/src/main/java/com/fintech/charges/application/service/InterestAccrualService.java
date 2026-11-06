package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.AccrualUnitStore.Basis;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.AccrualScheduleNotFoundException;
import com.fintech.charges.domain.AccrualScheduleStatus;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

@Service
public class InterestAccrualService {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualService.class);
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("360");

    private final AccrualScheduleRepository scheduleRepository;
    private final ChargeRecordRepository chargeRecordRepository;
    private final ChargeEventPublisher eventPublisher;
    private final ChargesProperties properties;

    public InterestAccrualService(AccrualScheduleRepository scheduleRepository,
                                   ChargeRecordRepository chargeRecordRepository,
                                   ChargeEventPublisher eventPublisher,
                                   ChargesProperties properties) {
        this.scheduleRepository    = scheduleRepository;
        this.chargeRecordRepository = chargeRecordRepository;
        this.eventPublisher        = eventPublisher;
        this.properties            = properties;
    }

    @Transactional(readOnly = true)
    public List<UUID> findScheduleIdsForAccrual(LocalDate date) {
        return scheduleRepository.findAllByStatus(AccrualScheduleStatus.ACTIVE.name())
                .stream()
                .filter(s -> s.needsAccrual(date))
                .map(AccrualSchedule::getScheduleId)
                .toList();
    }

    /**
     * Retrocede el reloj de todos los calendarios activos. <b>Sólo para siembra.</b>
     *
     * @return cuántos calendarios se movieron
     */
    @Transactional
    public int rewindAllTo(LocalDate date) {
        return rewindTo(date, null);
    }

    /** Rewinds one account's schedule, or every active one when {@code creditAccountId} is null. Seeding only. */
    @Transactional
    public int rewindTo(LocalDate date, UUID creditAccountId) {
        List<AccrualSchedule> schedules = creditAccountId == null
                ? scheduleRepository.findAllByStatus(AccrualScheduleStatus.ACTIVE.name())
                : scheduleRepository.findByCreditAccountId(creditAccountId).stream().toList();
        schedules.forEach(s -> {
            s.rewindAccrualTo(date);
            scheduleRepository.save(s);
        });
        return schedules.size();
    }

    @Transactional(readOnly = true)
    public List<UUID> findMoratoriumScheduleIds() {
        return findMoratoriumScheduleIds(BusinessClock.current().today());
    }

    /** Sólo los que de verdad deben devengar ese día: filtrar aquí evita N transacciones inútiles. */
    @Transactional(readOnly = true)
    public List<UUID> findMoratoriumScheduleIds(LocalDate date) {
        return scheduleRepository
                .findAllByStatusAndMoratoriumActive(AccrualScheduleStatus.ACTIVE.name(), true)
                .stream()
                .filter(s -> s.needsMoratoriumAccrual(date))
                .map(AccrualSchedule::getScheduleId)
                .toList();
    }

    // Called per-schedule by DailyAccrualJob — each gets its own transaction
    @Transactional
    public void accrueInterestForSchedule(UUID scheduleId, LocalDate accrualDate) {
        accrueInterestForSchedule(scheduleId, accrualDate, false);
    }

    /**
     * @param dayClose si el devengo es del cierre del día: el cargo entra al lote que cartera cuadra.
     * @return el interés ordinario que cobró; cero si ese día no hubo cargo
     */
    @Transactional
    public BigDecimal accrueInterestForSchedule(UUID scheduleId, LocalDate accrualDate, boolean dayClose) {
        AccrualSchedule schedule = find(scheduleId);
        // Outside the day close (test support, backfills) the basis is the live principal; a per-period account has no
        // plan figure here and charges nothing (its interest comes with the day close's snapshot).
        return accrueInterest(schedule, accrualDate, schedule.getPrincipalBalance(), null, null, dayClose);
    }

    /**
     * The day close path: the ordinary interest of {@code accrualDate} on the principal the portfolio
     * photographed at the end of that day, never on the live balance. A payment at 00:03 of D+1 or a retry
     * days later does not change what D accrues; the charge keeps that basis for the audit trail.
     */
    @Transactional
    public BigDecimal accrueInterestOnBasis(UUID scheduleId, LocalDate accrualDate, Basis basis) {
        return accrueInterest(find(scheduleId), accrualDate, basis.principal(), basis.scheduledInterest(),
                basis.scheduledTax(), true);
    }

    /**
     * The day close path for the moratorium: it runs when the snapshot shows overdue principal and the
     * oldest overdue installment plus the schedule's grace period is on or before {@code accrualDate}.
     * The live {@code moratoriumActive} flag is not consulted: it says how the account stands today, not
     * how it stood at the end of D.
     */
    @Transactional
    public BigDecimal accrueMoratoriumOnBasis(UUID scheduleId, LocalDate accrualDate, Basis basis) {
        AccrualSchedule schedule = find(scheduleId);
        if (!basis.moratoriumRunsOn(accrualDate, schedule.getGracePeriodDays())) {
            return BigDecimal.ZERO;
        }
        return accrueMoratorium(schedule, accrualDate, basis.overduePrincipal(), true);
    }

    private AccrualSchedule find(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new AccrualScheduleNotFoundException(scheduleId.toString()));
    }

    private BigDecimal accrueInterest(AccrualSchedule schedule, LocalDate accrualDate, BigDecimal basis,
                                      BigDecimal scheduledInterest, BigDecimal scheduledTax, boolean dayClose) {
        UUID scheduleId = schedule.getScheduleId();
        if (!schedule.needsAccrual(accrualDate)) {
            log.debug("Schedule {} already accrued for {} — skip", scheduleId, accrualDate);
            return BigDecimal.ZERO;
        }

        // El ordinario se devenga sobre TODO el saldo insoluto: es el precio del dinero prestado,
        // esté o no vencido. La distinción con el moratorio —que sí va sólo sobre lo vencido— es
        // justamente lo que separa un interés de una penalización. La única excepción la pone la foto de
        // cartera: las cuotas por vencer de una compra a meses sin intereses no entran a la base.
        if (basis == null || basis.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping accrual scheduleId={} — zero principal balance", scheduleId);
            schedule.markAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return BigDecimal.ZERO;
        }

        BigDecimal dailyRate     = schedule.getNominalRate().divide(DAYS_IN_YEAR, 10, RoundingMode.HALF_UP);
        // Per period (a distributor's placements): the interest each plan sets for its period, charged the day the
        // period falls due and not before; nothing on the other days. Daily: rate / 360 on the day's principal.
        BigDecimal interestAmount = schedule.accruesPerPeriod()
                ? (scheduledInterest == null ? BigDecimal.ZERO : scheduledInterest.setScale(2, RoundingMode.HALF_UP))
                : basis.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
        // Per period, the IVA each installment sets, rounded as its client pays it: the line owes what its clients pay.
        // Sixteen percent of the day's total interest, rounded once, was a cent or two away from it.
        BigDecimal taxAmount     = schedule.accruesPerPeriod() && scheduledTax != null
                ? scheduledTax.setScale(2, RoundingMode.HALF_UP)
                : interestAmount.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);

        if (interestAmount.signum() == 0) {
            // Un saldo tan chico que el día no llega al centavo: no hay cargo, pero el día queda devengado.
            // Intentar guardarlo en cero lo rechaza la tabla y detiene el cierre del día.
            log.debug("Skipping accrual scheduleId={} — no interest today basis={} perPeriod={}", scheduleId, basis,
                    schedule.accruesPerPeriod());
            schedule.markAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return BigDecimal.ZERO;
        }

        ChargeRecord interest = ChargeRecord.create(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                ChargeType.ORDINARY_INTEREST, basis, schedule.getNominalRate(),
                1, interestAmount, BigDecimal.ZERO, accrualDate, null);
        chargeRecordRepository.save(interest);

        ChargeRecord iva = iva(schedule, taxAmount, accrualDate, interest);
        if (dayClose) {
            interest.markDayClose(accrualDate);
            chargeRecordRepository.save(interest);
            if (iva != null) iva.markDayClose(accrualDate);
        }
        if (iva != null) chargeRecordRepository.save(iva);

        schedule.markAccruedFor(accrualDate);
        scheduleRepository.save(schedule);

        // ORDINARY_INTEREST → credit-portfolio accruedInterestBalance
        // La fecha de devengo viaja con el cargo: es la que decide el período contable de la póliza,
        // y sin ella un día devengado en diferido cae en el mes en que se procesó.
        // IVA → penaltyBalance
        afterCommit(() -> {
            publish(interest, ChargeType.ORDINARY_INTEREST.name(), interestAmount, accrualDate, dayClose);
            if (iva != null) publish(iva, "IVA", taxAmount, accrualDate, dayClose);
        });

        log.debug("OrdinaryInterest accrued scheduleId={} creditAccountId={} basis={} dailyRate={} amount={} tax={}",
                scheduleId, schedule.getCreditAccountId(), basis, dailyRate, interestAmount, taxAmount);
        return interestAmount;
    }

    // Called per-schedule by MoratoriumAccrualJob — each gets its own transaction
    @Transactional
    public void accrueMoratoriumForSchedule(UUID scheduleId, LocalDate accrualDate) {
        accrueMoratoriumForSchedule(scheduleId, accrualDate, false);
    }

    /**
     * @param dayClose si el devengo es del cierre del día: el cargo entra al lote que cartera cuadra.
     * @return el moratorio que cobró; cero si ese día no hubo cargo
     */
    @Transactional
    public BigDecimal accrueMoratoriumForSchedule(UUID scheduleId, LocalDate accrualDate, boolean dayClose) {
        AccrualSchedule schedule = find(scheduleId);
        if (!schedule.isMoratoriumActive()) {
            log.debug("Moratorium no longer active for scheduleId={} — skip", scheduleId);
            return BigDecimal.ZERO;
        }
        // BK-19 · CAPITAL VENCIDO, no el saldo completo. Con `principalBalance` un crédito de
        // $20 000 con una cuota vencida de $1 800 de capital devengaba mora sobre los $20 000.
        return accrueMoratorium(schedule, accrualDate, schedule.getOverduePrincipal(), dayClose);
    }

    private BigDecimal accrueMoratorium(AccrualSchedule schedule, LocalDate accrualDate, BigDecimal basis, boolean dayClose) {
        UUID scheduleId = schedule.getScheduleId();
        // TK-02: el devengo moratorio no tenía guarda de fecha — repetir la corrida del día
        // duplicaba el cargo sin resistencia. Lleva su propio reloj, separado del ordinario:
        // compartirlo dejaría sin devengar al job que llegara segundo.
        LocalDate last = schedule.getLastMoratoriumAccrualDate();
        if (!AccrualScheduleStatus.ACTIVE.name().equals(schedule.getStatus())
            || (last != null && !accrualDate.isAfter(last))) {
            log.debug("Moratorium already accrued scheduleId={} for {} — skip", scheduleId, accrualDate);
            return BigDecimal.ZERO;
        }

        if (basis == null || basis.compareTo(BigDecimal.ZERO) <= 0) {
            // Sin saldo no hay moratorio, pero el día queda marcado: si no, cada corrida volvería
            // a evaluar esta cuenta para no hacer nada.
            schedule.markMoratoriumAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return BigDecimal.ZERO;
        }

        BigDecimal dailyMoratoriumRate  = schedule.getMoratoriumRate().divide(DAYS_IN_YEAR, 10, RoundingMode.HALF_UP);
        BigDecimal delinquencyAmount     = basis.multiply(dailyMoratoriumRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount      = delinquencyAmount.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);

        if (delinquencyAmount.signum() == 0) {
            // Vencido de centavos: el día no llega al centavo de moratorio, pero queda devengado.
            schedule.markMoratoriumAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return BigDecimal.ZERO;
        }

        ChargeRecord delinquency = ChargeRecord.create(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                ChargeType.MORATORIUM_INTEREST, basis, schedule.getMoratoriumRate(),
                1, delinquencyAmount, BigDecimal.ZERO, accrualDate, null);
        chargeRecordRepository.save(delinquency);

        ChargeRecord iva = iva(schedule, taxAmount, accrualDate, delinquency);
        if (dayClose) {
            delinquency.markDayClose(accrualDate);
            chargeRecordRepository.save(delinquency);
            if (iva != null) iva.markDayClose(accrualDate);
        }
        if (iva != null) chargeRecordRepository.save(iva);

        schedule.markMoratoriumAccruedFor(accrualDate);
        scheduleRepository.save(schedule);

        // Both MORATORIUM_INTEREST and IVA → penaltyBalance
        afterCommit(() -> {
            publish(delinquency, ChargeType.MORATORIUM_INTEREST.name(), delinquencyAmount, accrualDate, dayClose);
            if (iva != null) publish(iva, "IVA", taxAmount, accrualDate, dayClose);
        });

        log.info("MoratoriumInterest accrued scheduleId={} creditAccountId={} basis={} moraRate={} amount={} tax={}",
                scheduleId, schedule.getCreditAccountId(), basis, dailyMoratoriumRate, delinquencyAmount, taxAmount);
        return delinquencyAmount;
    }

    /**
     * Publica cuando el cargo ya quedó guardado. Antes se publicaba dentro de la transacción: si el commit
     * fallaba —un IVA en 0.00 que la tabla rechaza— el cargo se deshacía aquí, pero el mensaje ya había
     * salido y cartera aplicaba un interés que cargos no tiene (24 cargos fantasma el 31 de octubre).
     * Al revés se arregla solo: un cargo guardado que no llegó lo reenvía el seguimiento del cierre con
     * el mismo id; un fantasma no lo recupera nada.
     *
     * <p>Si el envío falla, la excepción sube al job, que deja el día sin avisar y lo reintenta.
     */
    private static void afterCommit(Runnable envio) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            envio.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                envio.run();
            }
        });
    }

    /**
     * El IVA del interés, o {@code null} si redondea a cero: un interés de 0.02 da 0.0032 de IVA, que al
     * centavo no es nada. Un cargo de IVA en cero lo rechaza la tabla y tumbaría el devengo del día.
     */
    private static ChargeRecord iva(AccrualSchedule schedule, BigDecimal taxAmount, LocalDate accrualDate,
                                    ChargeRecord interes) {
        if (taxAmount.signum() == 0) return null;
        return ChargeRecord.createIva(schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                taxAmount, accrualDate, interes.getChargeId());
    }

    private void publish(ChargeRecord cargo, String tipo, BigDecimal importe, LocalDate fecha, boolean dayClose) {
        if (dayClose) {
            eventPublisher.publishChargeApplied(cargo.getChargeId().toString(), cargo.getCreditAccountId(), tipo,
                    importe, fecha, fecha);
        } else {
            eventPublisher.publishChargeApplied(cargo.getChargeId().toString(), cargo.getCreditAccountId(), tipo,
                    importe, fecha);
        }
    }
}
