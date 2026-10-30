package com.fintech.beneficiary.application.port.out;

/**
 * Manda el código al celular de la beneficiaria.
 *
 * <p>Puerto propio y no el {@code OtpService} de channel-mobile (§2, paso 6): aquél es un BFF y su
 * OTP está atado a la sesión de la app, que ella nunca instala. Aquí el código se acuña y se
 * comprueba en este servicio; lo único que se delega es la entrega.
 */
public interface BeneficiaryOtpSender {

    /**
     * @param phone   los 10 dígitos que tecleó el distribuidor
     * @param code    el código en claro — <b>el adaptador nunca lo escribe en un log</b>, salvo que
     *                sea el código fijo de desarrollo
     * @param devCode si es el código fijo de local; sólo entonces se puede registrar
     */
    void send(String phone, String code, boolean devCode);
}
