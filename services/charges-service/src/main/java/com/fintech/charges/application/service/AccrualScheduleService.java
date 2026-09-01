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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

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
        if (scheduleRepository.existsByCreditAccountId(creditAccountId)) {
            log.info("AccrualSchedule already exists for creditAccountId={} — skipping (idempotent)",
                    creditAccountId);
            return;
        }

        BigDecimal effectiveMoraRate = (moratoriumRate != null && moratoriumRate.compareTo(BigDecimal.ZERO) > 0)
                ? moratoriumRate
                : nominalRate.multiply(properties.getMoratoriumRateMultiplier())
                             .setScale(8, RoundingMode.HALF_UP);

        AccrualSchedule schedule = AccrualSchedule.create(
                creditAccountId, obligorPartyId, productType, productBehavior,
                nominalRate, effectiveMoraRate, principalBalance, principalBalance,
                properties.getGracePeriodDays());

        if (accrualStartDate != null) {
            schedule.arrancarDevengoEl(accrualStartDate);
        }
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
            if ("SETTLED".equalsIgnoreCase(accountStatus) || "WRITTEN_OFF".equalsIgnoreCase(accountStatus)) {
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
    public void actualizarMora(UUID creditAccountId, BigDecimal capitalVencido,
                               LocalDate vencimientoMasAntiguo) {
        scheduleRepository.findByCreditAccountId(creditAccountId).ifPresent(schedule -> {
            boolean estabaActiva = schedule.isMoratoriumActive();

            schedule.actualizarMora(capitalVencido, vencimientoMasAntiguo,
                    LocalDate.now(), schedule.getGracePeriodDays());
            scheduleRepository.save(schedule);

            if (estabaActiva != schedule.isMoratoriumActive()) {
                log.info("Mora {} creditAccountId={} capitalVencido={} desde={}",
                        schedule.isMoratoriumActive() ? "ENCENDIDA" : "APAGADA (cuenta curada)",
                        creditAccountId, capitalVencido, vencimientoMasAntiguo);
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
        LocalDate today      = LocalDate.now();

        ChargeRecord fee = ChargeRecord.create(
                accountId, schedule.getObligorPartyId(),
                ChargeType.OPENING_FEE, basis, feeRate, null, feeAmount, BigDecimal.ZERO, today, null);
        chargeRecordRepository.save(fee);

        ChargeRecord iva = ChargeRecord.createIva(
                accountId, schedule.getObligorPartyId(), taxAmount, today, fee.getChargeId());
        chargeRecordRepository.save(iva);

        eventPublisher.publishChargeApplied(fee.getChargeId().toString(), accountId,
                ChargeType.OPENING_FEE.name(), feeAmount);
        eventPublisher.publishChargeApplied(iva.getChargeId().toString(), accountId,
                "IVA", taxAmount);

        log.info("OPENING_FEE charged creditAccountId={} basis={} rate={} amount={} tax={}",
                accountId, basis, feeRate, feeAmount, taxAmount);
    }
}
