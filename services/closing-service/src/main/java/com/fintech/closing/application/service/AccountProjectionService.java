package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.BalanceMovementRepository;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.BillingCycle;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CutoffSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Mantiene lo que el cierre sabe de cada cuenta, alimentado por los eventos que cartera ya publica.
 *
 * <p>El cierre <b>no consulta cartera en línea</b>. Esta proyección es su fuente, y es eventual por
 * diseño: el sello declara con qué versión cerró, y lo que llegue después es extemporáneo.
 */
@Service
public class AccountProjectionService {

    private static final Logger log = LoggerFactory.getLogger(AccountProjectionService.class);

    /**
     * Cuánto calendario se materializa por adelantado para un revolvente, que no tiene plazo.
     *
     * <p>Se mide en <b>años</b> y no en número de cortes, y la diferencia importa desde que el ciclo
     * respeta la periodicidad del producto: eran 24 cortes fijos —pensados como 24 meses— y sobre un
     * producto semanal esos 24 cortes se agotan en cinco meses. Expresado en años, el horizonte es
     * el mismo para las tres cadencias y lo traduce {@code periodsPerYear()}.
     */
    private static final int REVOLVING_HORIZON_YEARS = 2;

    private final AccountCloseProfileRepository profiles;
    private final CutoffScheduleRepository cutoffs;
    private final ClosePolicyResolver policyResolver;
    private final CutoffScheduleDeriver deriver;
    private final BalanceMovementRepository movements;

    public AccountProjectionService(AccountCloseProfileRepository profiles,
                                     CutoffScheduleRepository cutoffs,
                                     ClosePolicyResolver policyResolver,
                                     CutoffScheduleDeriver deriver,
                                     BalanceMovementRepository movements) {
        this.profiles       = profiles;
        this.cutoffs        = cutoffs;
        this.policyResolver = policyResolver;
        this.deriver        = deriver;
        this.movements      = movements;
    }

    /**
     * Alta de la cuenta y <b>materialización de su calendario de corte</b>.
     *
     * <p>Idempotente: la reentrega del evento no duplica el calendario ni reabre un corte sellado.
     */
    @Transactional
    public void onAccountActivated(UUID creditAccountId, UUID obligorPartyId, String productType,
                                    String productBehavior, String orgUnitCode, LocalDate activatedOn,
                                    String paymentFrequency, Integer termPeriods,
                                    BigDecimal nominalRate, BigDecimal principalBalance) {
        onAccountActivated(creditAccountId, obligorPartyId, productType, productBehavior, orgUnitCode, activatedOn,
                paymentFrequency, termPeriods, nominalRate, principalBalance, null);
    }

    /** @param billingCycle the product's cycle pinned to the account; null falls back to the policy of its product type */
    public void onAccountActivated(UUID creditAccountId, UUID obligorPartyId, String productType,
                                    String productBehavior, String orgUnitCode, LocalDate activatedOn,
                                    String paymentFrequency, Integer termPeriods,
                                    BigDecimal nominalRate, BigDecimal principalBalance, BillingCycle billingCycle) {

        if (profiles.findById(creditAccountId).isPresent()) {
            log.debug("Cuenta {} ya proyectada — reentrega ignorada", creditAccountId);
            return;
        }

        AccountCloseProfile profile = profiles.save(AccountCloseProfile.fromActivation(
                creditAccountId, obligorPartyId, productType, productBehavior, orgUnitCode,
                activatedOn, paymentFrequency, termPeriods, nominalRate, principalBalance, billingCycle));

        ClosePolicy policy = policyResolver.require(null, productType, activatedOn);
        int cycles = profile.isRevolving() || termPeriods == null
                ? profile.cadence().periodsPerYear() * REVOLVING_HORIZON_YEARS
                : termPeriods;

        List<CutoffSchedule> calendar = deriver.deriveAll(profile, policy, cycles);
        if (!calendar.isEmpty()) {
            cutoffs.saveAll(calendar);
            CutoffSchedule first = calendar.get(0);
            profile.scheduleNextCutoff(first.getCycleNumber(), first.getCutoffDate());
            profiles.save(profile);
        }

        log.info("Cuenta proyectada {} producto={} regla={} cortes={} primerCorte={}",
                creditAccountId, productType, policy.cutoffRule(), calendar.size(),
                calendar.isEmpty() ? "—" : calendar.get(0).getCutoffDate());
    }

    /** Saldo nuevo. Un evento fuera de orden no retrocede lo recordado. */
    @Transactional
    public void onBalanceUpdated(UUID creditAccountId, BigDecimal principal, BigDecimal totalDebt,
                                  String accountStatus, long balanceVersion, String orgUnitCode) {
        onBalanceUpdated(creditAccountId, principal, totalDebt, accountStatus, balanceVersion, orgUnitCode, null);
    }

    /**
     * Saldo nuevo con su fecha valor. Además de la proyección, guarda cuánto movió el hecho: el corte de
     * D resta lo que tenga fecha posterior y sella el saldo al cierre de D.
     *
     * <p>Lo movido se mide contra el saldo anterior de la proyección. Los eventos de una cuenta llegan en
     * orden (misma llave, misma partición) y la versión impide aplicar uno viejo, así que la diferencia
     * es la del hecho.
     */
    @Transactional
    public void onBalanceUpdated(UUID creditAccountId, BigDecimal principal, BigDecimal totalDebt,
                                  String accountStatus, long balanceVersion, String orgUnitCode,
                                  LocalDate valueDate) {
        profiles.findById(creditAccountId).ifPresentOrElse(profile -> {
            profile.learnOrgUnit(orgUnitCode);
            BigDecimal capitalAntes = profile.getPrincipalBalance();
            BigDecimal deudaAntes   = profile.getTotalDebt();
            if (!profile.applyBalance(principal, totalDebt, accountStatus, balanceVersion, valueDate)) {
                log.debug("balance-updated viejo cuenta={} v={} — se ignora", creditAccountId, balanceVersion);
            } else if (valueDate != null) {
                movements.record(creditAccountId, balanceVersion, valueDate,
                        profile.getPrincipalBalance().subtract(capitalAntes),
                        profile.getTotalDebt().subtract(deudaAntes));
            }
            profiles.save(profile);
        }, () -> log.warn("balance-updated de una cuenta no proyectada: {} — se ignora hasta que llegue su alta",
                creditAccountId));
    }

    @Transactional
    public void onDelinquencyUpdated(UUID creditAccountId, int daysDelinquent) {
        profiles.findById(creditAccountId).ifPresent(profile -> {
            profile.applyDelinquency(daysDelinquent);
            profiles.save(profile);
        });
    }
}
