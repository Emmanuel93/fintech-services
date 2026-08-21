package com.fintech.beneficiary.application;

import com.fintech.beneficiary.domain.PlacementLimits;
import com.fintech.beneficiary.domain.VerificationMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * El alta previa: lo que el distribuidor teclea en la pantalla 18 más el monto y plazo de la 22.
 *
 * <p>Tres campos vienen ya resueltos por el llamador, y a propósito:
 *
 * <ul>
 *   <li>{@code distributorCreditAccountId} — la línea contra la que se coloca. Quien la resuelve
 *       es la capa que sabe hablar con credit-portfolio.</li>
 *   <li>{@code fortnightlyPayment} — lo calcula quien conoce la configuración del producto (tasa,
 *       cadencia, método). El agregado sólo exige que venga y sea positivo: no le toca cotizar.</li>
 *   <li>{@code inviteExpiresAt} — la ventana de la liga. El dominio no lee configuración.</li>
 * </ul>
 */
public record CreatePlacementCommand(
        UUID distributorPartyId,
        UUID distributorCreditAccountId,
        String beneficiaryFullName,
        String beneficiaryPhone,
        String beneficiaryRelationship,
        BigDecimal amount,
        int termFortnights,
        BigDecimal fortnightlyPayment,
        VerificationMode verificationMode,
        Instant inviteExpiresAt,
        /** Los límites del producto: tope por beneficiario, mínimo, plazos y escalones. */
        PlacementLimits limits,
        /** Lo que al distribuidor le queda de línea. Nulo salta la revisión de cupo. */
        BigDecimal availableLine,
        String correlationId) {}
