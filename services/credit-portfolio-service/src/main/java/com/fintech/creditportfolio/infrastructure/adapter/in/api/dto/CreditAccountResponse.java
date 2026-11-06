package com.fintech.creditportfolio.infrastructure.adapter.in.api.dto;

import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PaymentPlanSummary;
import com.fintech.creditportfolio.domain.PlacementGate;
import com.fintech.creditportfolio.domain.config.DistributorCommission;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CreditAccountResponse(
        UUID creditAccountId,
        UUID contractId,
        String contractNumber,
        String productCode,
        String productType,
        String productBehavior,
        UUID obligorPartyId,
        String status,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        Integer assignedTerm,
        BigDecimal creditLimit,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        /** IVA trasladado pendiente de cobro. Ni mora ni ingreso: impuesto por enterar. */
        BigDecimal ivaBalance,
        BigDecimal availableCredit,
        BigDecimal totalDebt,
        String amortizationType,
        String riskTier,
        String clabeAccount,
        int daysDelinquent,
        /** La sucursal que colocó el crédito, sellada al activarlo. Nula en los anteriores al sellado. */
        String originUnitCode,
        Instant createdAt,
        Instant activatedAt,

        // ── Avance del plan de pagos ──────────────────────────────────────
        //
        // El saldo dice cuánto se debe; esto dice en qué punto del plan va.
        // Sin estos campos el canal no puede mostrar "Pago 3 de 12" ni cuándo
        // vence el siguiente, que es lo primero que se mira al abrir la app.
        // Nulos en revolventes (no hay calendario) y mientras la cuenta no
        // tenga uno generado.
        Integer paidInstallments,
        Integer totalInstallments,
        Integer nextInstallmentNumber,
        LocalDate paymentDueDate,
        BigDecimal minimumPayment,
        BigDecimal principalPaid,
        BigDecimal overdueAmount,
        Integer overdueInstallments,

        // ── Cómo pagar ─────────────────────────────────────────────────────
        //
        // La CLABE de cobro de la persona (una para todos sus créditos) y la referencia numérica que dice cuál
        // crédito se paga. Las imprime el estado de cuenta; sin ellas el pago llega «por regla».
        Long paymentReference,
        String collectionClabe,

        // ── Línea de distribuidora ─────────────────────────────────────────
        //
        // Si hoy puede colocar y, si no, por qué y cuánto debe pagar para volver a hacerlo; y su parte de lo que
        // cobran sus clientes. Nulos en los productos que no pagan a una distribuidora.
        Boolean placementBlocked,
        String placementBlockReason,
        Integer overdueStatements,
        BigDecimal overdueStatementsAmount,
        LocalDate oldestOverdueStatementDue,
        BigDecimal distributorCommissionRate,
        String distributorCommissionSettlement
) {

    /** The account with its plan, its collection CLABE and, on a distributor line, its placement gate and commission. */
    public static CreditAccountResponse from(CreditAccount a, PaymentPlanSummary plan, String collectionClabe,
                                             PlacementGate gate, DistributorCommission commission) {
        CreditAccountResponse base = from(a, plan, collectionClabe);
        if (gate == null && commission == null) return base;
        return new CreditAccountResponse(base.creditAccountId(), base.contractId(), base.contractNumber(), base.productCode(),
                base.productType(), base.productBehavior(), base.obligorPartyId(), base.status(), base.nominalRate(),
                base.moratoriumRate(), base.assignedTerm(), base.creditLimit(), base.principalBalance(),
                base.accruedInterestBalance(), base.penaltyBalance(), base.ivaBalance(), base.availableCredit(),
                base.totalDebt(), base.amortizationType(), base.riskTier(), base.clabeAccount(), base.daysDelinquent(),
                base.originUnitCode(), base.createdAt(), base.activatedAt(), base.paidInstallments(),
                base.totalInstallments(), base.nextInstallmentNumber(), base.paymentDueDate(), base.minimumPayment(),
                base.principalPaid(), base.overdueAmount(), base.overdueInstallments(), base.paymentReference(),
                base.collectionClabe(),
                gate == null ? null : gate.blocked(),
                gate == null ? null : gate.reason(),
                gate == null ? null : gate.overdueStatements(),
                gate == null ? null : gate.overdueAmount(),
                gate == null ? null : gate.oldestDueDate(),
                commission == null ? null : commission.rate(),
                commission == null ? null : commission.settlement());
    }

    /** Sin calendario: los campos del plan van nulos, no en cero. */
    public static CreditAccountResponse from(CreditAccount a) {
        return from(a, PaymentPlanSummary.empty());
    }

    public static CreditAccountResponse from(CreditAccount a, PaymentPlanSummary plan) {
        return from(a, plan, null);
    }

    public static CreditAccountResponse from(CreditAccount a, PaymentPlanSummary plan, String collectionClabe) {
        return new CreditAccountResponse(
                a.getCreditAccountId(),
                a.getContractId(),
                a.getContractNumber(),
                a.getProductCode(),
                a.getProductType(),
                a.getProductBehavior(),
                a.getObligorPartyId(),
                a.getStatus() != null ? a.getStatus().name() : null,
                a.getNominalRate(),
                a.getMoratoriumRate(),
                a.getAssignedTerm(),
                a.getCreditLimit(),
                a.getPrincipalBalance(),
                a.getAccruedInterestBalance(),
                a.getPenaltyBalance(),
                a.getIvaBalance(),
                a.getAvailableCredit(),
                a.getTotalDebt(),
                a.getAmortizationType(),
                a.getRiskTier(),
                a.getClabeAccount(),
                a.getDaysDelinquent(),
                a.getOriginUnitCode(),
                a.getCreatedAt(),
                a.getActivatedAt(),
                plan.paidInstallments(),
                plan.totalInstallments(),
                plan.nextInstallmentNumber(),
                plan.nextDueDate(),
                plan.nextInstallmentAmount(),
                plan.principalPaid(),
                plan.overdueAmount(),
                plan.overdueInstallments(),
                a.getPaymentReference(),
                collectionClabe,
                null, null, null, null, null, null, null);
    }
}
