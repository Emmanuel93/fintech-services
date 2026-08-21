package com.fintech.creditportfolio.application;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateCreditAccountCommand(
        UUID contractId,
        String contractNumber,
        UUID obligorPartyId,
        String productCode,
        Integer productVersion,
        String productType,
        String productBehavior,
        BigDecimal approvedAmount,
        BigDecimal approvedLine,
        Integer assignedTerm,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        String amortizationType,
        BigDecimal openingFeeRate,
        String clabeAccount,
        String riskTier,
        /** Distribuidor que respalda el crédito. Viaja al evento de alta; commission lo proyecta. */
        String promoterCode,
        /**
         * Sucursal que colocó el crédito. Distinta del {@link #promoterCode()} — se confundían, y
         * el resultado era contabilidad atribuida a una sucursal que no existe. Null si el
         * originador no la resolvió: la reconciliación del backoffice la rellena después.
         */
        String originUnitCode,
        /**
         * IVA a trasladar, resuelto de la sucursal que coloca; {@code null} usa el nacional.
         *
         * <p>Viaja en el comando y no se consulta a sales-org desde aquí: cartera no conoce el árbol
         * comercial, y meterle una llamada síncrona al camino de activación añade un modo de fallo
         * —el crédito no nace porque el organigrama no contesta— a cambio de un dato que quien
         * origina ya tiene a la mano.
         */
        java.math.BigDecimal vatRate,
        String beneficiaryName,
        String beneficiaryTaxId
) {}
