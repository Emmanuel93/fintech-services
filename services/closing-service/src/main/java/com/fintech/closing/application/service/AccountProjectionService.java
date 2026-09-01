package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.domain.AccountCloseProfile;
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

    /** Cuántos cortes se materializan por adelantado para un revolvente, que no tiene plazo. */
    private static final int CICLOS_REVOLVENTE = 24;

    private final AccountCloseProfileRepository profiles;
    private final CutoffScheduleRepository cutoffs;
    private final ClosePolicyResolver policyResolver;
    private final CutoffScheduleDeriver deriver;

    public AccountProjectionService(AccountCloseProfileRepository profiles,
                                     CutoffScheduleRepository cutoffs,
                                     ClosePolicyResolver policyResolver,
                                     CutoffScheduleDeriver deriver) {
        this.profiles       = profiles;
        this.cutoffs        = cutoffs;
        this.policyResolver = policyResolver;
        this.deriver        = deriver;
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

        if (profiles.findById(creditAccountId).isPresent()) {
            log.debug("Cuenta {} ya proyectada — reentrega ignorada", creditAccountId);
            return;
        }

        AccountCloseProfile perfil = profiles.save(AccountCloseProfile.fromActivation(
                creditAccountId, obligorPartyId, productType, productBehavior, orgUnitCode,
                activatedOn, paymentFrequency, termPeriods, nominalRate, principalBalance));

        ClosePolicy politica = policyResolver.require(null, productType, activatedOn);
        int ciclos = perfil.isRevolving() || termPeriods == null ? CICLOS_REVOLVENTE : termPeriods;

        List<CutoffSchedule> calendario = deriver.deriveAll(perfil, politica, ciclos);
        if (!calendario.isEmpty()) {
            cutoffs.saveAll(calendario);
            CutoffSchedule primero = calendario.get(0);
            perfil.scheduleNextCutoff(primero.getCycleNumber(), primero.getCutoffDate());
            profiles.save(perfil);
        }

        log.info("Cuenta proyectada {} producto={} regla={} cortes={} primerCorte={}",
                creditAccountId, productType, politica.cutoffRule(), calendario.size(),
                calendario.isEmpty() ? "—" : calendario.get(0).getCutoffDate());
    }

    /** Saldo nuevo. Un evento fuera de orden no retrocede lo recordado. */
    @Transactional
    public void onBalanceUpdated(UUID creditAccountId, BigDecimal principal, BigDecimal totalDebt,
                                  String accountStatus, long balanceVersion, String orgUnitCode) {
        profiles.findById(creditAccountId).ifPresentOrElse(perfil -> {
            perfil.learnOrgUnit(orgUnitCode);
            if (!perfil.applyBalance(principal, totalDebt, accountStatus, balanceVersion)) {
                log.debug("balance-updated viejo cuenta={} v={} — se ignora", creditAccountId, balanceVersion);
            }
            profiles.save(perfil);
        }, () -> log.warn("balance-updated de una cuenta no proyectada: {} — se ignora hasta que llegue su alta",
                creditAccountId));
    }

    @Transactional
    public void onDelinquencyUpdated(UUID creditAccountId, int daysDelinquent) {
        profiles.findById(creditAccountId).ifPresent(perfil -> {
            perfil.applyDelinquency(daysDelinquent);
            profiles.save(perfil);
        });
    }
}
