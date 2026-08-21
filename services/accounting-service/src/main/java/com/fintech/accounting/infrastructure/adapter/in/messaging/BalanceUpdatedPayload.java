package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code credit-portfolio.balance-updated} — fuente primaria (triggerEvent → plantilla). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal totalDebt,
        String triggerEvent,
        String accountStatus,
        long balanceVersion,
        /**
         * La sucursal que colocó el crédito, sellada al activarlo.
         *
         * <p>Nula en los eventos anteriores a que cartera empezara a sellarla, y por eso accounting la
         * recuerda en su shadow: exigirla en cada payload haría que un solo productor despistado
         * borrara la atribución de una rama entera sin que nadie se enterara.
         */
        String originUnitCode,
        /** Cuándo ocurrió el hecho. De aquí sale el período contable, no del reloj del consumidor. */
        java.time.Instant occurredOn,
        /**
         * El importe del hecho, con signo, tal como lo calculó cartera. Nulo en productores viejos.
         *
         * <p>Deducirlo restando saldos era frágil: dos eventos del mismo crédito procesados fuera de
         * orden producían un delta que arrastraba lo que no le tocaba, y como se tomaba en valor
         * absoluto, la magnitud de un pago acababa asentada como si fuera un devengo de interés.
         */
        BigDecimal eventAmount,
        /** Cuánto movió el hecho en cada componente, con signo. Nulos en productores viejos. */
        BigDecimal principalDelta,
        BigDecimal interestDelta,
        BigDecimal penaltyDelta
) {}
