package com.fintech.beneficiary.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Los estados de una colocación, y las únicas transiciones que existen.
 *
 * <p>Ocho de estos once son los estados <b>públicos</b>: los que la app pinta y los que el
 * distribuidor entiende. Los otros tres —{@code KYC_COMPLETED}, {@code DISBURSING} y
 * {@code FAILED}— existen porque el buró y el desembolso son asíncronos y pueden fallar sin que
 * nadie haya decidido nada:
 *
 * <ul>
 *   <li>{@code KYC_COMPLETED} — el expediente ya existe pero el buró todavía no responde. Sin este
 *       estado, un buró lento sería indistinguible de un KYC incompleto.</li>
 *   <li>{@code DISBURSING} — la línea <b>no se aparta al invitar</b> (regla 1 del diseño), así que
 *       dos colocaciones pueden aprobarse contra la misma línea y la segunda encontrar saldo
 *       insuficiente. La autoridad del cupo es credit-portfolio, que contesta asíncrono. Sin este
 *       estado, {@code APPROVED} sería una promesa que el servicio no puede cumplir.</li>
 *   <li>{@code FAILED} — el resto de los estados terminales son decisiones de alguien; éste es el
 *       único que registra que algo se rompió, y por eso siempre lleva motivo.</li>
 * </ul>
 *
 * <p>Dos reglas del diseño quedan grabadas aquí y no en un servicio:
 *
 * <ol>
 *   <li><b>{@code CANCELLED} sólo antes del expediente.</b> El diseño dice «revocar antes del
 *       KYC». Una vez que la beneficiaria entregó sus documentos y firmó su autorización de buró,
 *       existe una persona con un expediente: la salida del distribuidor es {@code REJECTED}, que
 *       es una decisión con nombre, no una revocación silenciosa.</li>
 *   <li><b>{@code EXPIRED} sólo mientras la liga vive.</b> Los 7 días corren sobre la liga, no
 *       sobre la colocación. Terminado el KYC la liga se quema y el vencimiento deja de aplicar:
 *       lo que sigue esperando es el buró o la decisión, y ninguno de los dos caduca solo.</li>
 * </ol>
 */
public enum PlacementStatus {

    /** Liga enviada. La línea del distribuidor sigue intacta. */
    INVITED,
    /** La beneficiaria abrió la liga y pasó su OTP. */
    KYC_IN_PROGRESS,
    /** Los 7 pasos quedaron completos: hay prospecto, Party, relación y consentimientos. */
    KYC_COMPLETED,
    /** Scoring respondió. El distribuidor ya puede ver el reporte completo. */
    BUREAU_READY,
    /** El distribuidor decidió colocar y firmó que asume el riesgo. */
    APPROVED,
    /** Disposición THIRD_PARTY_CREDIT pedida; credit-portfolio tiene la última palabra. */
    DISBURSING,
    /** El SPEI llegó a la CLABE de la beneficiaria. */
    DISBURSED,
    /** La beneficiaria terminó de pagar: el calendario de la disposición quedó saldado. */
    PAID_OFF,

    /** El distribuidor vio el historial y decidió no colocar. */
    REJECTED,
    /** Pasaron los 7 días sin que la beneficiaria completara su KYC. */
    EXPIRED,
    /** El distribuidor revocó la liga antes de que hubiera expediente. */
    CANCELLED,
    /** KYC fallido, buró indisponible o disposición rechazada. Siempre con motivo. */
    FAILED;

    private static final Map<PlacementStatus, Set<PlacementStatus>> ALLOWED;

    static {
        EnumMap<PlacementStatus, Set<PlacementStatus>> allowed = new EnumMap<>(PlacementStatus.class);
        allowed.put(INVITED,         EnumSet.of(KYC_IN_PROGRESS, EXPIRED, CANCELLED));
        allowed.put(KYC_IN_PROGRESS, EnumSet.of(KYC_COMPLETED, EXPIRED, CANCELLED, FAILED));
        allowed.put(KYC_COMPLETED,   EnumSet.of(BUREAU_READY, FAILED));
        allowed.put(BUREAU_READY,    EnumSet.of(APPROVED, REJECTED));
        allowed.put(APPROVED,        EnumSet.of(DISBURSING, FAILED));
        allowed.put(DISBURSING,      EnumSet.of(DISBURSED, FAILED));
        allowed.put(DISBURSED,       EnumSet.of(PAID_OFF));
        // Los cinco terminales. Se declaran explícitamente y no por omisión para que agregar un
        // estado nuevo sin decidir sus salidas reviente aquí y no en producción.
        allowed.put(PAID_OFF,   EnumSet.noneOf(PlacementStatus.class));
        allowed.put(REJECTED,   EnumSet.noneOf(PlacementStatus.class));
        allowed.put(EXPIRED,    EnumSet.noneOf(PlacementStatus.class));
        allowed.put(CANCELLED,  EnumSet.noneOf(PlacementStatus.class));
        allowed.put(FAILED,     EnumSet.noneOf(PlacementStatus.class));

        if (allowed.size() != values().length) {
            throw new IllegalStateException(
                    "Cada PlacementStatus debe declarar sus destinos; faltan "
                            + (values().length - allowed.size()));
        }
        ALLOWED = Map.copyOf(allowed);
    }

    public boolean canTransitionTo(PlacementStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<PlacementStatus> allowedTargets() {
        return EnumSet.copyOf(ALLOWED.get(this));
    }

    /** Un estado terminal no tiene salidas: nada vuelve a moverse desde aquí. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * Si la liga sigue siendo redimible. Mientras sea cierto, el token puede acuñar sesión;
     * en cuanto deje de serlo, se quema (§3.4 del plan).
     */
    public boolean inviteIsLive() {
        return this == INVITED || this == KYC_IN_PROGRESS;
    }

    /** Si la línea del distribuidor ya quedó comprometida por esta colocación. */
    public boolean consumesLine() {
        return this == DISBURSING || this == DISBURSED || this == PAID_OFF;
    }

    /**
     * El estado tal como lo parsea la app (`PlacementStatus.fromName`).
     *
     * <p>Los estados internos son más finos que los de cable, y así debe ser: el dominio necesita
     * distinguir «esperando buró» de «esperando que abra la liga», y la app no. Dos colapsan a
     * propósito:
     *
     * <ul>
     *   <li>{@code KYC_COMPLETED → kycInProgress} — para el distribuidor sigue verificándose hasta
     *       que hay historial que ver.</li>
     *   <li>{@code DISBURSING → approved} — la app ya describe ese estado como «aprobada,
     *       desembolsando».</li>
     * </ul>
     *
     * <p>El {@code switch} es exhaustivo sin {@code default} a propósito: un estado nuevo no
     * compila hasta que alguien decide qué le enseña a la app. El contrato advierte que un valor
     * desconocido cae silenciosamente a {@code invited} —una colocación muerta pintada como viva—,
     * así que ese descuido tiene que doler en tiempo de compilación y no en la pantalla de alguien.
     */
    public String wireName() {
        return switch (this) {
            case INVITED         -> "invited";
            case KYC_IN_PROGRESS -> "kycInProgress";
            case KYC_COMPLETED   -> "kycInProgress";
            case BUREAU_READY    -> "bureauReady";
            case APPROVED        -> "approved";
            case DISBURSING      -> "approved";
            case DISBURSED       -> "active";
            case PAID_OFF        -> "paidOff";
            case REJECTED        -> "declined";
            case EXPIRED         -> "expired";
            case CANCELLED       -> "cancelled";
            case FAILED          -> "failed";
        };
    }
}
