package com.fintech.beneficiary.domain;

import com.fintech.shared.exception.DomainException;

import java.util.Map;

/**
 * Lo que la web pública de KYC le puede contestar a la beneficiaria cuando algo no procede.
 *
 * <p>Una sola jerarquía con su código de cable ({@code LIGA_NO_VALIDA}, {@code CODIGO_INCORRECTO},
 * …) y no las excepciones genéricas del servicio, porque <b>del otro lado no hay un integrador
 * sino una página</b>: la página decide qué pantalla mostrar por el código, y ese código es parte
 * del contrato con el frontend. Mezclarlo con los {@code ProblemDetail} de la API privada
 * obligaría a la web a entender dos formatos de error para un mismo flujo.
 *
 * <p>Los detalles ({@link #details()}) son lo que la pantalla necesita para no adivinar: cuántos
 * intentos quedan, qué campo está mal, qué falta del expediente.
 */
public abstract class PublicKycException extends DomainException {

    private final String wireCode;

    protected PublicKycException(String wireCode, String message) {
        super("BENEFICIARY_PUBLIC_" + wireCode, message);
        this.wireCode = wireCode;
    }

    /** El código que viaja en {@code error}. */
    public String wireCode() { return wireCode; }

    /** Campos extra del cuerpo de error. Vacío por defecto. */
    public Map<String, Object> details() { return Map.of(); }

    /**
     * La liga no sirve. <b>Un solo mensaje para todos los casos</b> —no existe, venció, se revocó,
     * ya se usó, la colocación ya no espera KYC—: distinguirlos le diría a quien prueba tokens al
     * azar cuáles existieron alguna vez.
     */
    public static class InviteNotValid extends PublicKycException {
        public InviteNotValid() {
            super("LIGA_NO_VALIDA", "Esta liga ya no es válida. Pídele a tu distribuidora una nueva.");
        }
    }

    /** Demasiados envíos, o la liga quedó congelada por códigos equivocados. */
    public static class TooManyAttempts extends PublicKycException {
        private final long retryAfterSeconds;

        public TooManyAttempts(String message, long retryAfterSeconds) {
            super("DEMASIADOS_INTENTOS", message);
            this.retryAfterSeconds = Math.max(retryAfterSeconds, 0);
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }

        @Override
        public Map<String, Object> details() {
            return Map.of("retryAfterSeconds", retryAfterSeconds);
        }
    }

    /** El código no coincide. Lleva cuántos intentos quedan para que la pantalla lo diga. */
    public static class WrongCode extends PublicKycException {
        private final int attemptsLeft;

        public WrongCode(int attemptsLeft) {
            super("CODIGO_INCORRECTO", "El código no coincide. Revisa el mensaje y vuelve a intentarlo.");
            this.attemptsLeft = Math.max(attemptsLeft, 0);
        }

        public int attemptsLeft() { return attemptsLeft; }

        @Override
        public Map<String, Object> details() {
            return Map.of("attemptsLeft", attemptsLeft);
        }
    }

    /** Sin sesión, o la de 15 minutos ya caducó: hay que volver a pasar el OTP. */
    public static class SessionExpired extends PublicKycException {
        public SessionExpired() {
            super("SESION_EXPIRADA", "Tu sesión terminó. Vuelve a abrir la liga para continuar.");
        }
    }

    /** Campos que no pasaron validación, con el motivo de cada uno. */
    public static class InvalidData extends PublicKycException {
        private final Map<String, String> fields;

        public InvalidData(Map<String, String> fields) {
            super("DATOS_INVALIDOS", "Hay datos por corregir.");
            this.fields = Map.copyOf(fields);
        }

        public Map<String, String> fields() { return fields; }

        @Override
        public Map<String, Object> details() {
            return Map.of("fields", fields);
        }
    }

    /** Se intentó firmar sin el expediente completo. */
    public static class IncompleteFile extends PublicKycException {
        private final java.util.List<String> missing;

        public IncompleteFile(java.util.List<String> missing) {
            super("EXPEDIENTE_INCOMPLETO", "Faltan pasos por completar antes de enviar.");
            this.missing = java.util.List.copyOf(missing);
        }

        public java.util.List<String> missing() { return missing; }

        @Override
        public Map<String, Object> details() {
            return Map.of("missing", missing);
        }
    }

    /**
     * Un servicio del que depende el cierre no respondió. Se reintenta sin perder nada: el
     * prospecto ya creado queda recordado en la sesión.
     */
    public static class DependencyUnavailable extends PublicKycException {
        public DependencyUnavailable(String message) {
            super("SERVICIO_NO_DISPONIBLE", message);
        }
    }
}
