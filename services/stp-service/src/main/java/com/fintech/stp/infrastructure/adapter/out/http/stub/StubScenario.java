package com.fintech.stp.infrastructure.adapter.out.http.stub;

import com.fintech.stp.domain.BanxicoResponseCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Predicate;

/**
 * Escenarios deterministas del stub, seleccionados por los centavos del monto.
 *
 * <p>Técnica clásica de sandbox de pagos: permite escribir pruebas de aceptación end-to-end sin un
 * solo mock. Mandas once órdenes y ejercitas las once ramas del servicio.
 *
 * <p>With {@code scenario-amounts: ARMED} (the local stack) only an amount a test armed plays its scenario; see
 * {@link #forAmount(BigDecimal, Predicate)}.
 */
public enum StubScenario {

    /** Camino feliz: STP acepta y la conciliación la reporta liquidada. */
    SETTLED(0),
    /** Registro rechazado con -1. El stub SÍ persiste la orden: existía de un intento anterior. */
    DUPLICATE_TRACKING_KEY(1),
    /** Registro rechazado con -200. Es el bug B1: debe terminar en REJECTED, nunca en éxito. */
    REJECTED_PLD(2),
    /** Registro rechazado con -30. Transitorio: reintento, no rechazo terminal (DB-08). */
    RETRYABLE(3),
    /** Aceptada y luego devuelta por el banco receptor. */
    RETURNED(4),
    /** Aceptada y luego cancelada. */
    CANCELLED(5),
    /** Aceptada y nunca aparece en la conciliación. Ejercita SO-06. */
    NEVER_SETTLES(6),
    /** Liquidada con un nombre de CEP distinto. Ejercita beneficiaryNameMatches=false. */
    NAME_MISMATCH(7),
    /** Liquidada con el sello alterado. Ejercita SO-04. */
    INVALID_SIGNATURE(8),
    /** La conciliación trae una clave de rastreo que nunca enviamos. Ejercita SO-03. */
    UNMATCHED_ENTRY(9),
    /** El registro tarda más que el timeout. Ejercita el circuit breaker (EG-04). */
    TIMEOUT(10),
    /**
     * A technical failure that a new attempt overcomes (D63): the first order of a beneficiary account and amount is
     * refused with -30 on every try, so the connector exhausts it; a later order of the same payment (another tracking
     * key: the caller's next attempt) is accepted and settles.
     */
    TRANSIENT_ONCE(11),
    /** Internal: the mark of the first order of a {@link #TRANSIENT_ONCE} payment. Never listed, never chosen by cents. */
    FIRST_ATTEMPT_REFUSED(-1);

    private final int cents;

    StubScenario(int cents) {
        this.cents = cents;
    }

    public int cents() {
        return cents;
    }

    /** Los centavos del monto eligen el escenario. Cualquier otro valor es el camino feliz. */
    public static StubScenario forAmount(BigDecimal amount) {
        if (amount == null) {
            return SETTLED;
        }
        int cents = amount.setScale(2, RoundingMode.HALF_EVEN)
                .remainder(BigDecimal.ONE)
                .movePointRight(2)
                .intValue();
        for (StubScenario scenario : values()) {
            if (scenario.cents == cents) {
                return scenario;
            }
        }
        return SETTLED;
    }

    /**
     * The scenario when only some amounts may play one: an armed amount plays its cents' scenario, and any other is the
     * happy path whatever its cents. {@code armed} is asked only about an amount whose cents name a scenario.
     */
    public static StubScenario forAmount(BigDecimal amount, Predicate<BigDecimal> armed) {
        StubScenario byCents = forAmount(amount);
        return byCents == SETTLED || armed.test(amount) ? byCents : SETTLED;
    }

    /** Código de rechazo en el registro, o {@code null} si el escenario acepta la orden. */
    public BanxicoResponseCode registrationRejection() {
        return switch (this) {
            case DUPLICATE_TRACKING_KEY -> BanxicoResponseCode.CLAVE_RASTREO_DUPLICADA;
            case REJECTED_PLD -> BanxicoResponseCode.RECHAZO_POR_PLD;
            case RETRYABLE -> BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS;
            default -> null;
        };
    }

    /** Estado con el que aparece en la conciliación, o {@code null} si nunca aparece. */
    public String reconciliationStatus() {
        return switch (this) {
            case RETURNED -> "D";
            case CANCELLED -> "CL";
            case NEVER_SETTLES, REJECTED_PLD, FIRST_ATTEMPT_REFUSED -> null;
            default -> "LQ";
        };
    }
}
