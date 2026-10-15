package com.fintech.beneficiary.domain;

/**
 * El veredicto de listas sobre la beneficiaria, tal como lo produjo cumplimiento.
 *
 * <h2>Por qué la colocación necesita esto y no le basta su propio dictamen de identidad</h2>
 *
 * Son dos preguntas distintas y ninguna contesta la otra. {@link IdentityDecision} dice <b>«es quien
 * dice ser»</b>; esto dice <b>«no está en listas negras ni es persona políticamente expuesta»</b>.
 * Una beneficiaria puede tener su INE perfectamente cotejada y estar publicada en una lista de
 * sancionados: la primera comprobación la deja pasar y la segunda es la única que la detiene.
 *
 * <p>La regla del negocio lo dice en una línea: la decisión de colocar es del distribuidor
 * <b>si y sólo si</b> se pudo validar la identidad <b>y</b> la persona no aparece en listas. Hasta
 * ahora {@code Placement.approve(...)} sólo comprobaba la mitad.
 *
 * <h2>Quién lo decide, y por qué no se decide aquí</h2>
 *
 * Lo produce {@code compliance} (D16), que es quien tiene las listas cargadas y versionadas, y
 * viaja hasta aquí por la ficha de la persona en {@code party}. Este servicio no lo calcula ni lo
 * interpreta: lo <b>obedece</b>. Una coincidencia no la despeja la distribuidora ni esta colocación
 * — la adjudica cumplimiento, con autor y argumento.
 *
 * @param status      el estado tal cual: {@code CLEARED}, {@code PENDING}, {@code POTENTIAL_MATCH},
 *                    {@code CONFIRMED_MATCH} o {@code UNKNOWN} cuando no se pudo consultar
 * @param blockReason por qué no pasa, para que el rechazo lleve motivo y no sólo un no
 */
public record ScreeningVerdict(String status, String blockReason) {

    public static final String CLEARED = "CLEARED";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * No se pudo consultar el veredicto. <b>Bloquea</b>, y lo dice.
     *
     * <p>Existe como fábrica con nombre para que «no sé» nunca se escriba como un {@code null} que
     * el llamador trate como vacío: no haber podido comprobar no es haber comprobado, y la
     * diferencia es la que separa este control de uno decorativo.
     */
    public static ScreeningVerdict unavailable(String detail) {
        return new ScreeningVerdict(UNKNOWN,
                "no se pudo comprobar si la beneficiaria está en listas: " + detail);
    }

    /**
     * Si la colocación puede aprobarse por el lado de listas. <b>Escrito en positivo.</b>
     *
     * <p>La forma natural —«bloquea si es POTENTIAL_MATCH o CONFIRMED_MATCH»— se rompe en silencio
     * el día que aparece un estado nuevo: lo desconocido pasaría. Así, lo desconocido se detiene.
     */
    public boolean allowsPlacement() {
        return CLEARED.equals(status);
    }
}
