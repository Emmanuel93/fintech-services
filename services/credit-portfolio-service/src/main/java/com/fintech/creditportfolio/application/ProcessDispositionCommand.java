package com.fintech.creditportfolio.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Petición de disposición sobre una línea ya activa.
 *
 * <p><b>No lleva el tipo de disposición.</b> Lo llevaba, y era un agujero: una petición sobre una
 * {@code DISTRIBUTOR_LINE} que mandara {@code "SELF_USE"} —o un valor basura, que caía al mismo
 * default silencioso— acreditaba el dinero a la distribuidora en vez de mandarlo a la beneficiaria.
 * El tipo es del <b>producto</b> y lo resuelve cartera desde sus {@code Capabilities}, la misma
 * fuente que ya usaba al activar (BK-13).
 */
public record ProcessDispositionCommand(
        String sourceEventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        UUID beneficiaryPartyId,
        String payeeAccount,
        /**
         * A cuántos períodos se amortiza esta disposición.
         *
         * <p>Una línea revolvente no tiene un plazo: lo tiene <b>cada disposición</b>. Es la misma
         * mecánica de una tarjeta con compras a meses — la línea vive, cada compra se amortiza por
         * su cuenta— y es el dato que decide el vendedor al colocar: «a cuántos meses se lo dejas».
         *
         * <p>Nulo cae al plazo por defecto del producto.
         */
        Integer termPeriods
) {}
