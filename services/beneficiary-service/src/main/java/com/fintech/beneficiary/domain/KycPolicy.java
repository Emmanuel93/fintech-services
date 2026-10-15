package com.fintech.beneficiary.domain;

/**
 * Qué verificación de identidad exige el producto de esta colocación (F3b).
 *
 * <p>Es la proyección de {@code Capabilities.kyc} del catálogo, y sustituye a
 * {@code fintech.beneficiary.identity-verification.mode} — una propiedad del servicio que obligaba
 * a que todos los productos de un ambiente compartieran regla.
 *
 * <p><b>El fallo seguro es exigir, no omitir.</b> Cuando no se sabe de qué producto es una
 * colocación —las anteriores a F3b no guardan {@code product_code}— o el catálogo todavía no ha
 * publicado su política, se devuelve {@link #requiredManual()}: se exige el KYC y lo resuelve una
 * persona. Nunca el objeto apagado. Un hueco de configuración debe costar trabajo de analista, no
 * gente registrada sin verificar.
 *
 * @param kycRequired           si el producto exige verificación de identidad
 * @param amlRequired           si además exige revisión de listas / PLD
 * @param automationEnabled     si se intenta resolver con un proveedor antes de mandarlo a una persona
 * @param provider              {@code INCODE} · {@code JUMIO} · {@code NONE}
 * @param manualFallbackAllowed si al fallar el proveedor puede resolverlo una persona
 */
public record KycPolicy(
        boolean kycRequired,
        boolean amlRequired,
        boolean automationEnabled,
        String provider,
        boolean manualFallbackAllowed) {

    public static final String PROVIDER_NONE = "NONE";

    /** El default seguro: se exige y lo resuelve una persona. */
    public static KycPolicy requiredManual() {
        return new KycPolicy(true, true, false, PROVIDER_NONE, true);
    }

    public boolean hasProvider() {
        return provider != null && !provider.isBlank() && !PROVIDER_NONE.equals(provider);
    }

    /**
     * Si hay que preguntarle al proveedor.
     *
     * <p>Las tres condiciones juntas y no sólo la bandera: automatizar una verificación que el
     * producto no exige no es nada, y «automatización encendida sin proveedor» es una configuración
     * que el catálogo rechaza pero que una fila vieja de la proyección podría todavía tener.
     */
    public boolean attemptsProvider() {
        return kycRequired && automationEnabled && hasProvider();
    }
}
