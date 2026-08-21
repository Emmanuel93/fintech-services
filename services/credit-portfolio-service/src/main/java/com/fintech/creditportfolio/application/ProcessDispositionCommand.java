package com.fintech.creditportfolio.application;

import java.math.BigDecimal;
import java.util.UUID;

public record ProcessDispositionCommand(
        String sourceEventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
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
