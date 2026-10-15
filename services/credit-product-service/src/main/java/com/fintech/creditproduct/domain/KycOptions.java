package com.fintech.creditproduct.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Qué verificación de identidad exige el producto, y cómo se resuelve.
 *
 * <p><b>Por qué vive en el producto y no en una bandera del servicio.</b> Hasta ahora la exigencia
 * de KYC era {@code BENEFICIARY_KYC_SIMULATION_ENABLED}: una variable de entorno, es decir una
 * decisión de despliegue. Eso tiene dos defectos que no se arreglan subiéndole el nombre. El
 * primero es que no se puede tener dos productos con reglas distintas en el mismo ambiente —y eso
 * es exactamente lo que un catálogo necesita, porque una línea de distribuidor y una tarjeta de uso
 * propio no exigen lo mismo—. El segundo es que apagar la verificación acaba siendo un
 * {@code docker compose up} con una variable distinta, sin autor, sin fecha y sin quién lo aprobó.
 * Como parte de la definición del producto, cambiarla es versionar el producto: queda firmado.
 *
 * <p><b>El proceso es uno solo.</b> Quien queda registrado pasa por el mismo KYC/AML, sea la
 * beneficiaria de una línea de distribuidor o el titular de un producto de uso propio. Lo que el
 * producto decide no es <i>qué</i> proceso se corre, sino si se exige, si se intenta automatizar y
 * con qué proveedor. Un solo proceso con configuración por producto — no dos procesos paralelos que
 * se desincronizan.
 *
 * <h2>La polaridad del default está invertida respecto a {@link PaymentOptions}</h2>
 *
 * En opciones de pago, lo que no se declara no se tiene: un producto viejo del catálogo no debe
 * amanecer permitiendo diferir compras porque nadie escribió el campo. Aquí es <b>al revés</b>.
 * {@link #requiredManual()} —y no un objeto apagado— es lo que devuelve {@link #orDefault} cuando
 * la fila no trae el bloque, porque el fallo seguro de una verificación de identidad es exigirla,
 * no omitirla. Un producto mal configurado debe mandar gente a revisión manual; jamás dejarla pasar
 * sin verificar.
 *
 * @param kycRequired           si el producto exige verificación de identidad. <b>Por omisión sí.</b>
 * @param amlRequired           si exige revisión de listas / PLD además de la identidad
 * @param automationEnabled     si se <i>intenta</i> resolver con un proveedor. Apagado significa
 *                              «siempre lo resuelve una persona», que es un estado legítimo y
 *                              distinto de «hay automatización y no sé con quién»
 * @param provider              {@code INCODE} · {@code JUMIO} · {@code NONE}. Dos campos y no uno
 *                              a propósito: {@code automationEnabled=false} y
 *                              {@code provider=NONE} dicen cosas distintas de un producto donde
 *                              alguien olvidó elegir proveedor
 * @param manualFallbackAllowed si al fallar o agotarse el proveedor puede resolverlo una persona.
 *                              A diferencia del buró —donde ningún analista puede inventar un
 *                              FICO— el KYC <b>sí</b> tiene salida humana, y apagarla deja al
 *                              producto sin ninguna
 */
public record KycOptions(
        boolean kycRequired,
        boolean amlRequired,
        boolean automationEnabled,
        String provider,
        boolean manualFallbackAllowed) {

    public static final String PROVIDER_NONE = "NONE";

    /** Se exige, la resuelve una persona. El default seguro cuando la configuración no dice nada. */
    public static KycOptions requiredManual() {
        return new KycOptions(true, true, false, PROVIDER_NONE, true);
    }

    /** Se exige y se intenta con un proveedor, con salida humana si falla. */
    public static KycOptions automatedWith(String provider) {
        return new KycOptions(true, true, true, provider, true);
    }

    /**
     * El producto no exige verificación.
     *
     * <p>Existe para poder declararlo <b>a propósito</b>, no para ser el valor por omisión: a un
     * producto que no exige KYC alguien tuvo que escribirle que no lo exige.
     */
    public static KycOptions notRequired() {
        return new KycOptions(false, false, false, PROVIDER_NONE, false);
    }

    /** Nunca nulo, y nunca permisivo: una fila sin bloque exige KYC manual. */
    public static KycOptions orDefault(KycOptions o) {
        return o != null ? o : requiredManual();
    }

    /** Si hay proveedor de verdad, y no el centinela ni un hueco. */
    public boolean hasProvider() {
        return provider != null && !provider.isBlank() && !PROVIDER_NONE.equals(provider);
    }

    /** Se intenta el proveedor sólo si además se exige el KYC: automatizar lo que no se pide no es nada. */
    public boolean attemptsProvider() {
        return kycRequired && automationEnabled && hasProvider();
    }

    /**
     * Combinaciones que no rompen nada y mienten sobre lo que el producto hace.
     *
     * <p>Mismo criterio que {@link PaymentOptions#inconsistencies()}: devuelve todas de una vez,
     * porque quien corrige una configuración quiere verlas juntas y no descubrir la siguiente en el
     * intento siguiente.
     */
    public List<String> inconsistencies() {
        List<String> males = new ArrayList<>();

        if (automationEnabled && !hasProvider()) {
            males.add("automationEnabled=true sin proveedor (" + provider + "): la automatización "
                    + "está declarada y no se puede ejercer nunca — todo caería a manual sin que "
                    + "nadie lo haya decidido");
        }
        if (!automationEnabled && hasProvider()) {
            males.add("provider=" + provider + " con automationEnabled=false: un proveedor "
                    + "configurado al que nunca se llama");
        }
        if (!kycRequired && (automationEnabled || hasProvider() || amlRequired)) {
            males.add("kycRequired=false pero hay verificación declarada (aml=" + amlRequired
                    + ", automation=" + automationEnabled + ", provider=" + provider + "): "
                    + "parámetros para algo que este producto no hace");
        }
        // Sin proveedor y sin salida humana no queda ningún camino: la solicitud se queda quieta.
        if (kycRequired && !automationEnabled && !manualFallbackAllowed) {
            males.add("kycRequired=true sin automatización y sin manualFallbackAllowed: no hay "
                    + "ningún camino para resolver el KYC y toda solicitud quedaría atorada");
        }
        return males;
    }
}
