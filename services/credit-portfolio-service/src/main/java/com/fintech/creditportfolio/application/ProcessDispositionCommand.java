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
         * El titular de {@code payeeAccount}: la beneficiaria en una colocación.
         *
         * <p>Va con la CLABE porque el destino es la terna (nombre, CLABE, RFC) y no la CLABE sola.
         * Sin él, el SPEI de una línea de distribuidor llevaba la CLABE de la beneficiaria con el
         * nombre y el RFC de la distribuidora: el banco receptor puede devolverlo por no coincidir
         * y el CEP dice que el dinero fue para quien no lo recibió.
         */
        String payeeName,
        /** RFC o CURP del titular de {@code payeeAccount}. Nulo = SPEI sin RFC, que es válido. */
        String payeeTaxId,
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
