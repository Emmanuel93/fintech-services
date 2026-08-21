package com.fintech.stp.domain.signing;

/**
 * Falla al firmar o al verificar. Es de dominio, no técnica: una orden que no se puede firmar no
 * sale, y una observación que no verifica no se aplica.
 */
public class StpSignatureException extends RuntimeException {

    public StpSignatureException(String message) {
        super(message);
    }

    public StpSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
