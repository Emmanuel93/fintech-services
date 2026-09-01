package com.fintech.creditproduct.domain;

import java.math.BigDecimal;

/**
 * Cómo se paga lo que se dispone: parcialidades, BNPL, salto de pago y elegibilidad para apoyos.
 *
 * <p><b>Va aparte de {@link Capabilities} y no como diez campos más</b> por una razón concreta: un
 * record de diecinueve posiciones se construye mal tarde o temprano, y ya pasó — una prueba de
 * contrato cazó dos campos que nunca se llegaron a poblar porque nadie notó que faltaban en la
 * llamada. Diez opciones que se leen juntas y cambian juntas son un objeto, no diez parámetros.
 *
 * @param installmentPlanMode  {@code AT_DISPOSITION} el vendedor fija el plazo al colocar ·
 *                             {@code POST_HOC} la compra nace revolvente y el titular la difiere
 *                             después · {@code NONE} no hay parcialidades
 * @param deferralCutoffRule   hasta cuándo se puede diferir una compra ya hecha:
 *                             {@code BEFORE_CUTOFF} · {@code BEFORE_DUE_DATE} · {@code NONE}
 * @param deferralDefaultTerm  plazo si el cliente no elige. <b>Default 3.</b>
 * @param deferralMinTerm      plazo mínimo admitido al diferir
 * @param deferralMaxTerm      plazo máximo admitido al diferir
 * @param bnplEnabled          si al originar se puede correr el arranque del pago
 * @param bnplMaxDeferralDays  tope de días que se puede correr
 * @param skipPaymentEnabled   si el producto admite saltar un pago
 * @param maxSkipsPerCycle     cuántos saltos por ciclo
 * @param skipMode             {@code GIFT} el período saltado NO devenga (recompensa real) ·
 *                             {@code DEFERRAL} sigue devengando y la cuota se corre
 * @param reliefEligible       si el producto admite programas de apoyo por contingencia
 * @param deferralRates        la tasa por banda de plazo. <b>Viaja en la configuración</b> y no se
 *                             consulta al catálogo en el momento de diferir: diferir es una acción
 *                             del cliente desde la app, y meterle una llamada síncrona entre
 *                             servicios la vuelve frágil justo donde el cliente está mirando
 */
public record OpcionesDePago(
        String installmentPlanMode,
        String deferralCutoffRule,
        Integer deferralDefaultTerm,
        Integer deferralMinTerm,
        Integer deferralMaxTerm,
        boolean bnplEnabled,
        Integer bnplMaxDeferralDays,
        boolean skipPaymentEnabled,
        Integer maxSkipsPerCycle,
        String skipMode,
        boolean reliefEligible,
        java.util.List<BandaDeDiferimiento> deferralRates) {

    /**
     * La tasa de diferir para una banda de plazo. Réplica de lo que `rate_cards` resuelve con
     * {@code purpose = 'DEFERRAL'}.
     *
     * <p>Un producto real ofrece «3 y 6 MSI, 9 al 18 %, 12 al 24 %»: es una tabla, no un valor.
     *
     * @param nominalRate cero es válido y es exactamente lo que un MSI es
     */
    public record BandaDeDiferimiento(int minTerm, int maxTerm, java.math.BigDecimal nominalRate) {

        public boolean cubre(int plazo) { return plazo >= minTerm && plazo <= maxTerm; }

        public boolean esMesesSinIntereses() { return nominalRate.signum() == 0; }
    }

    /** El plazo por defecto al diferir, cuando el producto no dice otra cosa. */
    public static final int PLAZO_POR_DEFECTO = 3;

    /**
     * Lo que aplica a un producto que no declara nada: sin parcialidades, sin BNPL, sin saltos.
     *
     * <p>Todo apagado a propósito. Un default permisivo convertiría cada producto viejo del
     * catálogo en uno que admite diferir y saltar pagos sin que nadie lo haya decidido.
     */
    public static OpcionesDePago ninguna() {
        return new OpcionesDePago("NONE", "NONE", PLAZO_POR_DEFECTO, 1, 24,
                false, null, false, 0, "DEFERRAL", false, java.util.List.of());
    }

    /** El calendario se genera al disponer: el vendedor fija «a cuántos meses se lo dejas». */
    public boolean planAlDisponer() { return "AT_DISPOSITION".equals(installmentPlanMode); }

    /** La compra nace revolvente pura y el titular decide diferirla <b>después</b>. */
    public boolean planPosterior()  { return "POST_HOC".equals(installmentPlanMode); }

    public boolean admiteDiferir()  { return planPosterior() && !"NONE".equals(deferralCutoffRule); }

    /** {@code GIFT}: el período saltado no devenga. Es una recompensa, no un aplazamiento. */
    public boolean saltoEsRegalo()  { return "GIFT".equals(skipMode); }

    public int plazoDeDiferimiento(Integer pedido) {
        int plazo = pedido != null ? pedido
                : (deferralDefaultTerm != null ? deferralDefaultTerm : PLAZO_POR_DEFECTO);
        if (deferralMinTerm != null && plazo < deferralMinTerm) {
            throw new IllegalArgumentException(
                    "El plazo " + plazo + " es menor al mínimo del producto (" + deferralMinTerm + ")");
        }
        if (deferralMaxTerm != null && plazo > deferralMaxTerm) {
            throw new IllegalArgumentException(
                    "El plazo " + plazo + " excede el máximo del producto (" + deferralMaxTerm + ")");
        }
        return plazo;
    }

    /**
     * La tasa con la que se difiere a este plazo.
     *
     * <p><b>Vacío significa que el producto no difiere a ese plazo</b>, y quien llama debe rechazar
     * la operación. No se cae a la tasa de originación: diferir al 36 % una compra que el cliente
     * creía a meses sin intereses es el error que este mecanismo existe para impedir.
     */
    public java.util.Optional<java.math.BigDecimal> tasaDeDiferimiento(int plazo) {
        if (deferralRates == null) return java.util.Optional.empty();
        return deferralRates.stream()
                .filter(b -> b.cubre(plazo))
                // La banda más estrecha gana: es la más específica sobre ese plazo.
                .min(java.util.Comparator.comparingInt(b -> b.maxTerm() - b.minTerm()))
                .map(BandaDeDiferimiento::nominalRate);
    }

    /** Nunca nulo: un producto sin opciones declaradas se comporta como {@link #ninguna()}. */
    public static OpcionesDePago oNinguna(OpcionesDePago o) {
        return o != null ? o : ninguna();
    }
}
