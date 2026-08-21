package com.fintech.beneficiary.domain;

/**
 * El estado de la verificación de identidad, que es lo que la mesa de KYC mira.
 *
 * <p><b>Validamos identidad, no riesgo.</b> El score del beneficiario es informativo y no bloquea
 * la colocación: quien decide es la distribuidora. Lo único que detiene el depósito es una
 * identidad no comprobada, y esta enumeración existe para que eso se pueda ver y filtrar sin
 * confundirlo con el avance comercial de la colocación.
 *
 * <p>Por eso se deriva de {@link PlacementStatus} en vez de ser un campo propio: hoy el hecho
 * «su identidad quedó comprobada» y el hecho «su expediente quedó completo» son el mismo evento
 * ({@code KYC_COMPLETED}). El día que exista la captura granular —coincidencia facial, prueba de
 * vida, cotejo INE/RENAPO— esto pasa a ser un agregado propio con su propia evidencia, y esta
 * derivación se retira. Mientras tanto informa la verdad disponible sin inventar precisión.
 */
public enum IdentityStatus {

    /** Se le mandó la liga; todavía no empieza. */
    PENDING,
    /** Está en su verificación: abrió la liga y pasó su OTP. */
    IN_PROGRESS,
    /** Identidad comprobada. Es lo que habilita el depósito. */
    VERIFIED,
    /** No se comprobó: la liga venció, se revocó, o la verificación falló. */
    NOT_VERIFIED;

    /**
     * El estado de identidad de una colocación.
     *
     * <p><b>El veredicto explícito manda.</b> Sólo cuando nadie ha dictaminado se cae a derivar del
     * avance de la colocación, y esa derivación ya no dice {@code VERIFIED} nunca: antes decía que
     * estaba verificada por el mero hecho de que la beneficiaria terminara su KYC, que es
     * exactamente la confusión que este cambio deshace. Entregar sus documentos no es que alguien
     * los haya mirado.
     */
    public static IdentityStatus of(Placement placement) {
        return switch (placement.getIdentityDecision()) {
            case VERIFIED -> VERIFIED;
            case REJECTED -> NOT_VERIFIED;
            case PENDING  -> derivedFrom(placement.getStatus());
        };
    }

    /**
     * Lo que se puede decir de la identidad cuando nadie la ha revisado.
     *
     * <p>Se queda en {@code IN_PROGRESS} desde que abre la liga hasta que un analista dictamine:
     * el expediente completo la deja lista para revisión, no revisada.
     */
    private static IdentityStatus derivedFrom(PlacementStatus status) {
        return switch (status) {
            case INVITED -> PENDING;
            case KYC_IN_PROGRESS, KYC_COMPLETED, BUREAU_READY,
                 APPROVED, DISBURSING, DISBURSED, PAID_OFF, REJECTED -> IN_PROGRESS;
            case EXPIRED, CANCELLED, FAILED -> NOT_VERIFIED;
        };
    }

    /**
     * Si con esta identidad se puede depositar.
     *
     * <p>Ojo con no confundirlo con la decisión comercial: una colocación {@code REJECTED} —la
     * distribuidora vio el historial y dijo que no— puede tener identidad {@code VERIFIED}. Son
     * juicios de responsables distintos, y mezclar «no la identificamos» con «la identificamos y no
     * le prestaron» borraría de la mesa de KYC precisamente lo que la mesa existe para vigilar.
     */
    public boolean allowsDisbursement() {
        return this == VERIFIED;
    }
}
