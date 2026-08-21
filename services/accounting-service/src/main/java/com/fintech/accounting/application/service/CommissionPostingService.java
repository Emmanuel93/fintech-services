package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * El lado contable de las comisiones a distribuidores y promotores.
 *
 * <p>T6 sólo decide (devengar, revertir, liquidar); aquí se asienta el gasto, el pasivo y, al
 * liquidar, la salida de Bancos. No pasa por {@code posting_rules} porque viene de otra familia de
 * tópicos y las cuentas no dependen de un {@code triggerEvent} configurable.
 */
@Service
@Transactional
public class CommissionPostingService {

    private final AccountBalanceShadowRepository shadowRepository;
    private final VoucherPostingService ledger;

    public CommissionPostingService(AccountBalanceShadowRepository shadowRepository,
                                     VoucherPostingService ledger) {
        this.shadowRepository = shadowRepository;
        this.ledger           = ledger;
    }

    /** Comisión devengada → gasto por comisiones / comisiones por pagar. */
    public void onCommissionAccrued(UUID commissionId, UUID creditAccountId, BigDecimal amount, Instant occurredAt) {
        post(commissionId.toString(), "COMMISSION_ACCRUED", creditAccountId, amount, occurredAt,
                AccountCodes.GASTO_COMISIONES, AccountCodes.COMISIONES_POR_PAGAR,
                "Comisión devengada — distribuidor/promotor");
    }

    /** CM-05: pago devuelto → cancela el devengo. */
    public void onCommissionReversed(UUID commissionId, UUID creditAccountId, BigDecimal amount, Instant occurredAt) {
        post(commissionId + ":reversed", "COMMISSION_REVERSED", creditAccountId, amount, occurredAt,
                AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.GASTO_COMISIONES,
                "Comisión revertida — pago devuelto");
    }

    /**
     * Liquidación del lote → liquida el pasivo contra Bancos.
     *
     * <p>Sin crédito asociado: un lote paga muchas comisiones de muchos créditos a la vez, y
     * atribuirlo a uno sería inventarse el dato. Queda sin sucursal, contado aparte.
     */
    public void onCommissionLiquidated(UUID batchId, BigDecimal totalAmount, Instant occurredAt) {
        post(batchId.toString(), "COMMISSION_LIQUIDATED", null, totalAmount, occurredAt,
                AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.BANCOS,
                "Liquidación de comisiones (SPEI a distribuidor/promotor)");
    }

    private void post(String sourceEventId, String triggerEvent, UUID creditAccountId, BigDecimal amount,
                      Instant occurredAt, String debit, String credit, String concept) {
        if (amount == null || amount.signum() <= 0) return;
        String unit = creditAccountId == null ? null : shadowRepository.findById(creditAccountId)
                .map(AccountBalanceShadow::getOrgUnitCode).orElse(null);
        ledger.post(
                new VoucherRequest(sourceEventId, triggerEvent, occurredAt, creditAccountId, null, unit, concept),
                List.of(new Pair(debit, credit, amount, concept)));
    }
}
