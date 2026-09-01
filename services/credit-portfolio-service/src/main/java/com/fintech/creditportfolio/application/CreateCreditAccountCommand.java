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
        String beneficiaryTaxId,
        /**
         * Días de BNPL que el cliente <b>pidió</b> al originar, o {@code null} si no pidió ninguno.
         *
         * <p>BNPL es una decisión del alta, no una propiedad del producto. Lo que el producto trae
         * es el <b>tope</b> ({@code bnplMaxDeferralDays}), y aquí viaja lo solicitado dentro de ese
         * tope. Sin esta distinción, cartera no tenía forma de saber si el aplazamiento se había
         * pedido, y aplicaba el tope a todo el mundo: <b>ningún</b> préstamo personal empezaba a
         * pagar cuando debía.
         *
         * <p>Hoy llega nulo porque originación todavía no lo captura. Que el campo exista y sea
         * nulo es la diferencia entre «nadie lo pidió» y «no sabemos», y sólo el primero permite
         * no aplicarlo.
         */
        Integer bnplDeferralDays
) {}
