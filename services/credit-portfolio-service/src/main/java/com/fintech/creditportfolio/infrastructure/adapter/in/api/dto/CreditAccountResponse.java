package com.fintech.creditportfolio.infrastructure.adapter.in.api.dto;

import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PaymentPlanSummary;

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
        Integer overdueInstallments
) {

    /** Sin calendario: los campos del plan van nulos, no en cero. */
    public static CreditAccountResponse from(CreditAccount a) {
        return from(a, PaymentPlanSummary.empty());
    }

    public static CreditAccountResponse from(CreditAccount a, PaymentPlanSummary plan) {
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
                plan.overdueInstallments());
    }
}
