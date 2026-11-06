package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

@Service
@Transactional
public class AccrualScheduleService {

    private static final Logger log = LoggerFactory.getLogger(AccrualScheduleService.class);

    private final AccrualScheduleRepository scheduleRepository;
    private final ChargeRecordRepository chargeRecordRepository;
    private final ChargeEventPublisher eventPublisher;
    private final ChargesProperties properties;
    private final AccountBalanceSnapshotRepository snapshotRepository;

    public AccrualScheduleService(AccrualScheduleRepository scheduleRepository,
                                   ChargeRecordRepository chargeRecordRepository,
                                   ChargeEventPublisher eventPublisher,
                                   ChargesProperties properties,
                                   AccountBalanceSnapshotRepository snapshotRepository) {
        this.scheduleRepository    = scheduleRepository;
        this.chargeRecordRepository = chargeRecordRepository;
        this.eventPublisher        = eventPublisher;
        this.properties            = properties;
        this.snapshotRepository    = snapshotRepository;
    }

    public void createFromActivation(UUID creditAccountId, UUID obligorPartyId,
                                      String productType, String productBehavior,
                                      BigDecimal nominalRate, BigDecimal moratoriumRate,
                                      BigDecimal principalBalance, BigDecimal openingFeeRate) {
        createFromActivation(creditAccountId, obligorPartyId, productType, productBehavior,
                nominalRate, moratoriumRate, principalBalance, openingFeeRate, null);
    }

    /**
     * @param accrualStartDate BNPL: desde cuándo devenga. Nulo = desde el alta (BK-28)
     */
    public void createFromActivation(UUID creditAccountId, UUID obligorPartyId,
                                      String productType, String productBehavior,
                                      BigDecimal nominalRate, BigDecimal moratoriumRate,
                                      BigDecimal principalBalance, BigDecimal openingFeeRate,
                                      LocalDate accrualStartDate) {
        createFromActivation(creditAccountId, obligorPartyId, productType, productBehavior, nominalRate,
                moratoriumRate, principalBalance, openingFeeRate, accrualStartDate, null);
    }

    /** @param graceDays the product's grace period before the moratorium; null uses the service default */
    public void createFromActivation(UUID creditAccountId, UUID obligorPartyId,
                                      String productType, String productBehavior,
                                      BigDecimal nominalRate, BigDecimal moratoriumRate,
                                      BigDecimal principalBalance, BigDecimal openingFeeRate,
                                      LocalDate accrualStartDate, Integer graceDays) {
        createFromActivation(creditAccountId, obligorPartyId, productType, productBehavior, nominalRate, moratoriumRate,
                principalBalance, openingFeeRate, accrualStartDate, graceDays, null);
    }

    /** @param interestAccrual the product's interest mode (DAILY or PER_PERIOD); null is daily */
    public void createFromActivation(UUID creditAccountId, UUID obligorPartyId,
                                      String productType, String productBehavior,
                                      BigDecimal nominalRate, BigDecimal moratoriumRate,
                                      BigDecimal principalBalance, BigDecimal openingFeeRate,
                                      LocalDate accrualStartDate, Integer graceDays, String interestAccrual) {
        if (scheduleRepository.existsByCreditAccountId(creditAccountId)) {
            log.info("AccrualSchedule already exists for creditAccountId={} — skipping (idempotent)",
                    creditAccountId);
            return;
        }

        BigDecimal effectiveMoratoriumRate = (moratoriumRate != null && moratoriumRate.compareTo(BigDecimal.ZERO) > 0)
                ? moratoriumRate
                : nominalRate.multiply(properties.getMoratoriumRateMultiplier())
                             .setScale(8, RoundingMode.HALF_UP);

        AccrualSchedule schedule = AccrualSchedule.create(
                creditAccountId, obligorPartyId, productType, productBehavior,
                nominalRate, effectiveMoratoriumRate, principalBalance, principalBalance,
                graceDays != null && graceDays >= 0 ? graceDays : properties.getGracePeriodDays());

        if (accrualStartDate != null) {
            schedule.startAccrualOn(accrualStartDate);
        }
        schedule.accrueInterest(interestAccrual);
        scheduleRepository.save(schedule);
        log.info("AccrualSchedule created scheduleId={} creditAccountId={} productType={} nominalRate={}{}",
                schedule.getScheduleId(), creditAccountId, productType, nominalRate,
                accrualStartDate != null ? " devenga desde " + accrualStartDate : "");

        BigDecimal feeRate = (openingFeeRate != null && openingFeeRate.compareTo(BigDecimal.ZERO) > 0)
                ? openingFeeRate : properties.getOpeningFeeRate();
        if (feeRate.compareTo(BigDecimal.ZERO) > 0) {
            chargeOpeningFee(schedule, feeRate);
        }
    }

    public void updateBalanceFromEvent(UUID creditAccountId, BigDecimal newBalance, String accountStatus) {
        scheduleRepository.findByCreditAccountId(creditAccountId).ifPresent(schedule -> {
            schedule.updateBalance(newBalance);
            // A cancelled credit (D63) accrues nothing more either; its charges are reversed on its own fact.
            if ("SETTLED".equalsIgnoreCase(accountStatus) || "WRITTEN_OFF".equalsIgnoreCase(accountStatus)
                    || "CANCELLED".equalsIgnoreCase(accountStatus) || "CLOSED".equalsIgnoreCase(accountStatus)) {
                schedule.close();
                log.info("AccrualSchedule CLOSED creditAccountId={} trigger={}", creditAccountId, accountStatus);
            }
            scheduleRepository.save(schedule);
        });
    }

    /**
     * BK-18 · cartera midió la mora; aquí se enciende o se apaga.
     *
     * <p><b>El circuito estaba cortado justo aquí.</b> Cartera calculaba el DPD desde las cuotas
     * vencidas y publicaba {@code delinquency-status-updated}. Nadie lo escuchaba.
     * {@code activateMoratorium()} tenía cero llamadores de producción —sólo dos pruebas— y la
     * cuenta contable {@code 4102} (ingreso moratorio) nunca recibió un abono. No es que la mora
     * fuera difícil de producir: no existía.
     *
     * <p>Se conecta <b>después</b> de corregir la base (BK-19). Al revés, la primera corrida habría
     * encendido el cobro sobre el saldo completo en toda la cartera vencida.
     */
    /**
     * A restructure took effect: the account accrues the agreed rate from now on. Without this the agreement only
     * changed the portfolio's record and the client kept paying the old rate (2 Nov, E04: 24 % agreed, 32 % charged).
     */
    public void applyRestructure(UUID creditAccountId, BigDecimal newRate) {
        scheduleRepository.findByCreditAccountId(creditAccountId).ifPresentOrElse(schedule -> {
            BigDecimal anterior = schedule.getNominalRate();
            schedule.changeNominalRate(newRate);
            scheduleRepository.save(schedule);
            log.info("Reestructura: tasa de devengo {} → {} creditAccountId={}", anterior, newRate, creditAccountId);
        }, () -> log.warn("Reestructura sin calendario de devengo creditAccountId={} — nada que cambiar", creditAccountId));
    }

    public void updateMoratorium(UUID creditAccountId, BigDecimal overduePrincipal,
                               LocalDate vencimientoMasAntiguo) {
        scheduleRepository.findByCreditAccountId(creditAccountId).ifPresent(schedule -> {
            boolean estabaActiva = schedule.isMoratoriumActive();

            schedule.updateMoratorium(overduePrincipal, vencimientoMasAntiguo,
                    BusinessClock.current().today(), schedule.getGracePeriodDays());
            scheduleRepository.save(schedule);

            if (estabaActiva != schedule.isMoratoriumActive()) {
                log.info("Mora {} creditAccountId={} capitalVencido={} desde={}",
                        schedule.isMoratoriumActive() ? "ENCENDIDA" : "APAGADA (cuenta curada)",
                        creditAccountId, overduePrincipal, vencimientoMasAntiguo);
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<AccrualSchedule> findByCreditAccountId(UUID creditAccountId) {
        return scheduleRepository.findByCreditAccountId(creditAccountId);
    }

    private void chargeOpeningFee(AccrualSchedule schedule, BigDecimal feeRate) {
        UUID accountId = schedule.getCreditAccountId();

        // PRE-CHECK: skip if local snapshot indicates a terminal account (eventual consistency)
        snapshotRepository.findByCreditAccountId(accountId).ifPresent(snapshot -> {
            if (!snapshot.isChargeable()) {
                throw new IllegalStateException(
                        "OPENING_FEE pre-check failed: account " + accountId
                        + " is in terminal state " + snapshot.getAccountStatus());
            }
        });

        if (chargeRecordRepository.existsByChargeTypeAndCreditAccountId(
                ChargeType.OPENING_FEE.name(), accountId)) {
            log.warn("OPENING_FEE already exists for creditAccountId={} — skipping (CR-05)", accountId);
            return;
        }

        BigDecimal basis     = schedule.getApprovedAmount();
        BigDecimal feeAmount = basis.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = feeAmount.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);
        LocalDate today      = BusinessClock.current().today();

        // Nothing to charge: a revolving line is approved with a limit, not a disbursed amount, so its basis is
        // zero. A zero record would violate the amount check at commit, after the event had already gone out:
        // the activation was then retried and never consumed, and the account never got its schedule.
        if (feeAmount.signum() <= 0) {
            log.info("OPENING_FEE omitted creditAccountId={} basis={} rate={}: the fee rounds to zero", accountId, basis, feeRate);
            return;
        }

        ChargeRecord fee = ChargeRecord.create(
                accountId, schedule.getObligorPartyId(),
                ChargeType.OPENING_FEE, basis, feeRate, null, feeAmount, BigDecimal.ZERO, today, null);
        chargeRecordRepository.save(fee);

        ChargeRecord iva = taxAmount.signum() > 0
                ? ChargeRecord.createIva(accountId, schedule.getObligorPartyId(), taxAmount, today, fee.getChargeId())
                : null;
        if (iva != null) {
            chargeRecordRepository.save(iva);
        }

        // The events leave after the records are committed: what the portfolio applies always exists here.
        afterCommit(() -> {
            eventPublisher.publishChargeApplied(fee.getChargeId().toString(), accountId,
                    ChargeType.OPENING_FEE.name(), feeAmount);
            if (iva != null) {
                eventPublisher.publishChargeApplied(iva.getChargeId().toString(), accountId, "IVA", taxAmount);
            }
        });

        log.info("OPENING_FEE charged creditAccountId={} basis={} rate={} amount={} tax={}",
                accountId, basis, feeRate, feeAmount, taxAmount);
    }

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
}
