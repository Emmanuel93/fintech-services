package com.fintech.compliance.domain;

/** La carga no se pudo completar. Nunca llegó a activarse, así que la lista vigente sigue siendo la anterior. */
public class WatchlistLoadFailedException extends RuntimeException {

    private final String sourceCode;

    public WatchlistLoadFailedException(String sourceCode, String reason, Throwable cause) {
        super("No se pudo cargar la lista " + sourceCode + ": " + reason, cause);
        this.sourceCode = sourceCode;
    }

    public String sourceCode() { return sourceCode; }
}
