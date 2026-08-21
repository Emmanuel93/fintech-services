package com.fintech.shared.exception;

/**
 * Excepción base para todas las violaciones de invariantes de dominio.
 *
 * <p>Las subclases deben ser específicas al módulo que las lanza:
 * {@code PartyNotFoundException}, {@code InsufficientCreditException}, etc.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
