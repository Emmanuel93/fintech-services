package com.fintech.creditportfolio.domain.config;

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
     * Las contradicciones de esta configuración, cada una con su frase. Vacía si es coherente.
     *
     * <p>Un producto no se configura campo a campo: los campos <b>se condicionan entre sí</b>, y una
     * combinación puede ser inválida sin que ninguno de sus valores lo sea. Eso no lo caza revisar
     * el JSON a ojo, y es exactamente lo que ha costado tiempo: parámetros de diferimiento en un
     * producto que no difiere, un modo de salto declarado sobre un salto apagado, un tope de BNPL
     * en un producto que no lo ofrece. Ninguno rompe nada — todos mienten sobre lo que el producto
     * hace.
     *
     * <p>Devuelve la lista en vez de lanzar en el primer fallo: quien corrige una configuración
     * quiere verlas todas de una vez, no descubrir la siguiente en el intento siguiente.
     */
    public java.util.List<String> incoherencias() {
        java.util.List<String> males = new java.util.ArrayList<>();
        String modo = installmentPlanMode == null ? "NONE" : installmentPlanMode;

        // El diferimiento posterior necesita saber HASTA CUÁNDO se puede diferir. Sin ventana, la
        // capacidad está declarada y no se puede ejercer nunca.
        if ("POST_HOC".equals(modo) && (deferralCutoffRule == null || "NONE".equals(deferralCutoffRule))) {
            males.add("installmentPlanMode=POST_HOC exige una deferralCutoffRule: sin ventana, "
                    + "diferir una compra no se puede ejercer nunca");
        }

        if (!"NONE".equals(modo)) {
            if (deferralMinTerm == null || deferralMaxTerm == null || deferralMinTerm > deferralMaxTerm) {
                males.add("installmentPlanMode=" + modo + " exige un rango de plazos válido; hay "
                        + deferralMinTerm + "-" + deferralMaxTerm);
            } else if (deferralDefaultTerm != null
                    && (deferralDefaultTerm < deferralMinTerm || deferralDefaultTerm > deferralMaxTerm)) {
                males.add("deferralDefaultTerm=" + deferralDefaultTerm + " cae fuera del rango "
                        + deferralMinTerm + "-" + deferralMaxTerm + ": el cliente que no elige "
                        + "recibiría un plazo que el producto no admite");
            }
        } else if (deferralMinTerm != null || deferralMaxTerm != null || deferralDefaultTerm != null) {
            males.add("installmentPlanMode=NONE pero hay plazos de diferimiento declarados "
                    + "(" + deferralMinTerm + "-" + deferralMaxTerm + ", por omisión "
                    + deferralDefaultTerm + "): parámetros para algo que este producto no hace");
        }

        // Sólo POST_HOC necesita tabla de tasas propia. En AT_DISPOSITION el plan nace con la
        // colocación y cobra la tasa del producto: no hay diferimiento que tarificar.
        if ("POST_HOC".equals(modo)) {
            males.addAll(huecosDeBandas());
        } else if (deferralRates != null && !deferralRates.isEmpty()) {
            males.add("installmentPlanMode=" + modo + " no difiere después, pero declara "
                    + deferralRates.size() + " bandas de tasa que nadie va a consultar");
        }

        if (skipPaymentEnabled && (maxSkipsPerCycle == null || maxSkipsPerCycle < 1)) {
            males.add("skipPaymentEnabled=true con maxSkipsPerCycle=" + maxSkipsPerCycle
                    + ": el salto está ofrecido y no se puede usar ni una vez");
        }
        if (!skipPaymentEnabled && maxSkipsPerCycle != null && maxSkipsPerCycle > 0) {
            males.add("skipPaymentEnabled=false con maxSkipsPerCycle=" + maxSkipsPerCycle
                    + ": un tope para algo apagado");
        }

        if (bnplEnabled && (bnplMaxDeferralDays == null || bnplMaxDeferralDays <= 0)) {
            males.add("bnplEnabled=true con bnplMaxDeferralDays=" + bnplMaxDeferralDays
                    + ": el producto dice ofrecer BNPL de cero días, que es no ofrecerlo");
        }
        return males;
    }

    /** Las bandas tienen que cubrir el rango entero, sin huecos ni solapes. */
    private java.util.List<String> huecosDeBandas() {
        if (deferralMinTerm == null || deferralMaxTerm == null) {
            return java.util.List.of();
        }
        if (deferralRates == null || deferralRates.isEmpty()) {
            return java.util.List.of("installmentPlanMode=POST_HOC sin bandas de tasa: no hay tasa "
                    + "con la que diferir a ningún plazo");
        }
        java.util.List<BandaDeDiferimiento> ordenadas = deferralRates.stream()
                .sorted(java.util.Comparator.comparing(BandaDeDiferimiento::minTerm))
                .toList();

        java.util.List<String> males = new java.util.ArrayList<>();
        int esperado = deferralMinTerm;
        for (BandaDeDiferimiento b : ordenadas) {
            if (b.minTerm() > esperado) {
                males.add("los plazos " + esperado + "-" + (b.minTerm() - 1) + " no tienen banda de "
                        + "tasa: diferir ahí no sabría qué cobrar");
            } else if (b.minTerm() < esperado) {
                males.add("la banda " + b.minTerm() + "-" + b.maxTerm() + " se solapa con la "
                        + "anterior: dos tasas para el mismo plazo");
            }
            esperado = Math.max(esperado, b.maxTerm() + 1);
        }
        if (esperado <= deferralMaxTerm) {
            males.add("los plazos " + esperado + "-" + deferralMaxTerm + " no tienen banda de tasa: "
                    + "el producto los admite y no sabría qué cobrar");
        }
        return males;
    }

    /**
     * Cuándo arranca el plan según los días de BNPL que el cliente <b>pidió</b>.
     *
     * <p>{@code bnplMaxDeferralDays} es un <b>tope</b>, no una cifra a aplicar — el nombre lo dice.
     * Cartera lo usaba como el valor y no miraba si alguien lo había pedido, así que todo crédito
     * de un producto con BNPL habilitado nacía con el aplazamiento máximo. Como el préstamo
     * personal lo tiene habilitado, <b>ninguno empezaba a pagar cuando debía</b>.
     *
     * <p>La regla vive aquí, con la configuración, y no en el servicio: es la configuración quien
     * sabe qué significa cada uno de sus campos, y tenerla suelta fue lo que permitió confundir un
     * límite con un valor.
     *
     * <ul>
     *   <li>Sin solicitud, no hay BNPL. Nulo y cero significan lo mismo: nadie lo pidió.</li>
     *   <li>Si el producto no lo admite, la solicitud se ignora — no se aplica a la fuerza.</li>
     *   <li>Pedir de más se <b>recorta</b> al tope. El producto ya declaró hasta dónde espera, y
     *       negar el alta entera por pedir de más convierte un límite en un obstáculo.</li>
     * </ul>
     */
    public java.time.LocalDate arranqueDeBnpl(java.time.LocalDate hoy, Integer solicitados) {
        if (solicitados == null || solicitados <= 0) {
            return hoy;
        }
        if (!bnplEnabled || bnplMaxDeferralDays == null || bnplMaxDeferralDays <= 0) {
            return hoy;
        }
        return hoy.plusDays(Math.min(solicitados, bnplMaxDeferralDays));
    }

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
