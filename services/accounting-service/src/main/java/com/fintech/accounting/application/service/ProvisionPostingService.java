package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.ProvisionLedgerRepository;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.domain.ProvisionLedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GL-09: asienta la estimación preventiva por <strong>delta</strong> contra {@link
 * ProvisionLedgerEntry}. Risk publica el monto absoluto cada noche; aquí se asienta solo el cambio:
 * delta&gt;0 = deterioro (gasto), delta&lt;0 = cura/liberación (reversión).
 */
@Service
@Transactional
public class ProvisionPostingService {

    private static final Logger log = LoggerFactory.getLogger(ProvisionPostingService.class);

    private final ProvisionLedgerRepository provisionLedgerRepository;
    private final AccountBalanceShadowRepository shadowRepository;
    private final VoucherPostingService ledger;

    public ProvisionPostingService(ProvisionLedgerRepository provisionLedgerRepository,
                                    AccountBalanceShadowRepository shadowRepository,
                                    VoucherPostingService ledger) {
        this.provisionLedgerRepository = provisionLedgerRepository;
        this.shadowRepository          = shadowRepository;
        this.ledger                    = ledger;
    }

    public void onRiskAssessment(UUID creditAccountId, UUID obligorPartyId,
                                 BigDecimal provisionAmount, Instant calculatedAt) {
        if (provisionAmount == null) return;
        ProvisionLedgerEntry entry = provisionLedgerRepository.findById(creditAccountId)
                .orElseGet(() -> ProvisionLedgerEntry.init(creditAccountId));
        BigDecimal delta = entry.book(provisionAmount);
        provisionLedgerRepository.save(entry);

        if (delta.signum() == 0) return;

        Instant at = calculatedAt != null ? calculatedAt : Instant.now();
        String sourceEventId = "PROV-" + creditAccountId + "-" + at.toEpochMilli();
        // La sucursal sale del shadow: risk no la conoce ni tiene por qué. Es la misma que llevan las
        // demás pólizas del crédito, así que el deterioro se atribuye a quien colocó el préstamo.
        String unit = shadowRepository.findById(creditAccountId)
                .map(AccountBalanceShadow::getOrgUnitCode).orElse(null);

        boolean deterioro = delta.signum() > 0;
        ledger.post(
                new VoucherRequest(sourceEventId, "PROVISION_EPR", at, creditAccountId, obligorPartyId, unit,
                        deterioro ? "Estimación preventiva — deterioro" : "Estimación preventiva — liberación"),
                List.of(deterioro
                        ? new Pair(AccountCodes.GASTO_ESTIMACION, AccountCodes.ESTIMACION_PREVENTIVA, delta,
                                   "Constitución de reserva")
                        : new Pair(AccountCodes.ESTIMACION_PREVENTIVA, AccountCodes.GASTO_ESTIMACION, delta.abs(),
                                   "Liberación de reserva por mejora de riesgo")));
        log.debug("Provisión creditAccountId={} delta={}", creditAccountId, delta);
    }
}
