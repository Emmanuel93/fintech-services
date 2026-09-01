package com.fintech.origination.application;

import java.util.UUID;

public record SignContractCommand(
        UUID applicationId,
        String clabeAccount,
        String signatureProof,
        String documentRef,
        /**
         * Días que el cliente pide esperar antes de su primer pago, o {@code null} si no pide nada.
         *
         * <p>Es la decisión de BNPL, y se toma <b>al firmar</b>: corre el devengo y el primer
         * vencimiento a la vez. El producto sólo declara el tope; cartera recorta lo pedido a ese
         * tope y no al revés.
         */
        Integer bnplDeferralDays
) {}
