package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        List<AccrualSchedule> activos = scheduleRepository.findAllByStatus(AccrualScheduleStatus.ACTIVE.name());
        activos.forEach(s -> {
            s.rewindAccrualTo(date);
            scheduleRepository.save(s);
        });
        return activos.size();
    }

    @Transactional(readOnly = true)
    public List<UUID> findMoratoriumScheduleIds() {
        return findMoratoriumScheduleIds(LocalDate.now());
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
        AccrualSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new AccrualScheduleNotFoundException(scheduleId.toString()));

        if (!schedule.needsAccrual(accrualDate)) {
            log.debug("Schedule {} already accrued for {} — skip", scheduleId, accrualDate);
            return;
        }

        // El ordinario se devenga sobre TODO el saldo insoluto: es el precio del dinero prestado,
        // esté o no vencido. La distinción con el moratorio —que sí va sólo sobre lo vencido— es
        // justamente lo que separa un interés de una penalización.
        BigDecimal basis = schedule.getPrincipalBalance();
        if (basis == null || basis.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Skipping accrual scheduleId={} — zero principal balance", scheduleId);
            schedule.markAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return;
        }

        BigDecimal dailyRate     = schedule.getNominalRate().divide(DAYS_IN_YEAR, 10, RoundingMode.HALF_UP);
        BigDecimal interestAmount = basis.multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount     = interestAmount.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);

        ChargeRecord interest = ChargeRecord.create(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                ChargeType.ORDINARY_INTEREST, basis, schedule.getNominalRate(),
                1, interestAmount, BigDecimal.ZERO, accrualDate, null);
        chargeRecordRepository.save(interest);

        ChargeRecord iva = ChargeRecord.createIva(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                taxAmount, accrualDate, interest.getChargeId());
        chargeRecordRepository.save(iva);

        // ORDINARY_INTEREST → credit-portfolio accruedInterestBalance
        // La fecha de devengo viaja con el cargo: es la que decide el período contable de la póliza,
        // y sin ella un día devengado en diferido cae en el mes en que se procesó.
        eventPublisher.publishChargeApplied(interest.getChargeId().toString(),
                schedule.getCreditAccountId(), ChargeType.ORDINARY_INTEREST.name(), interestAmount, accrualDate);
        // IVA → penaltyBalance
        eventPublisher.publishChargeApplied(iva.getChargeId().toString(),
                schedule.getCreditAccountId(), "IVA", taxAmount, accrualDate);

        schedule.markAccruedFor(accrualDate);
        scheduleRepository.save(schedule);

        log.debug("OrdinaryInterest accrued scheduleId={} creditAccountId={} basis={} dailyRate={} amount={} tax={}",
                scheduleId, schedule.getCreditAccountId(), basis, dailyRate, interestAmount, taxAmount);
    }

    // Called per-schedule by MoratoriumAccrualJob — each gets its own transaction
    @Transactional
    public void accrueMoratoriumForSchedule(UUID scheduleId, LocalDate accrualDate) {
        AccrualSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new AccrualScheduleNotFoundException(scheduleId.toString()));

        if (!schedule.isMoratoriumActive()) {
            log.debug("Moratorium no longer active for scheduleId={} — skip", scheduleId);
            return;
        }

        // TK-02: el devengo moratorio no tenía guarda de fecha — repetir la corrida del día
        // duplicaba el cargo sin resistencia. Lleva su propio reloj, separado del ordinario:
        // compartirlo dejaría sin devengar al job que llegara segundo.
        if (!schedule.needsMoratoriumAccrual(accrualDate)) {
            log.debug("Moratorium already accrued scheduleId={} for {} — skip", scheduleId, accrualDate);
            return;
        }

        // BK-19 · CAPITAL VENCIDO, no el saldo completo. Con `principalBalance` un crédito de
        // $20 000 con una cuota vencida de $1 800 de capital devengaba mora sobre los $20 000.
        BigDecimal basis = schedule.getOverduePrincipal();
        if (basis == null || basis.compareTo(BigDecimal.ZERO) <= 0) {
            // Sin saldo no hay moratorio, pero el día queda marcado: si no, cada corrida volvería
            // a evaluar esta cuenta para no hacer nada.
            schedule.markMoratoriumAccruedFor(accrualDate);
            scheduleRepository.save(schedule);
            return;
        }

        BigDecimal dailyMoraRate  = schedule.getMoratoriumRate().divide(DAYS_IN_YEAR, 10, RoundingMode.HALF_UP);
        BigDecimal moraAmount     = basis.multiply(dailyMoraRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount      = moraAmount.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);

        ChargeRecord mora = ChargeRecord.create(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                ChargeType.MORATORIUM_INTEREST, basis, schedule.getMoratoriumRate(),
                1, moraAmount, BigDecimal.ZERO, accrualDate, null);
        chargeRecordRepository.save(mora);

        ChargeRecord iva = ChargeRecord.createIva(
                schedule.getCreditAccountId(), schedule.getObligorPartyId(),
                taxAmount, accrualDate, mora.getChargeId());
        chargeRecordRepository.save(iva);

        // Both MORATORIUM_INTEREST and IVA → penaltyBalance
        eventPublisher.publishChargeApplied(mora.getChargeId().toString(),
                schedule.getCreditAccountId(), ChargeType.MORATORIUM_INTEREST.name(), moraAmount, accrualDate);
        eventPublisher.publishChargeApplied(iva.getChargeId().toString(),
                schedule.getCreditAccountId(), "IVA", taxAmount, accrualDate);

        schedule.markMoratoriumAccruedFor(accrualDate);
        scheduleRepository.save(schedule);

        log.info("MoratoriumInterest accrued scheduleId={} creditAccountId={} basis={} moraRate={} amount={} tax={}",
                scheduleId, schedule.getCreditAccountId(), basis, dailyMoraRate, moraAmount, taxAmount);
    }
}
