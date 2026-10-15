package com.fintech.compliance.domain;

/**
 * Una carga que no produjo ninguna fila válida.
 *
 * <p>Tiene tipo propio y no es un `IllegalStateException` porque es el fallo que hay que poder
 * distinguir y alertar: una lista de sancionados vacía no existe. Cuando aparece, lo que cambió es
 * el layout o la descarga, y en ambos casos la respuesta es mirar el archivo — no reintentar.
 */
public class EmptyWatchlistLoadException extends RuntimeException {

    private final String sourceCode;
    private final int rowsRead;

    public EmptyWatchlistLoadException(String sourceCode, int rowsRead) {
        super("La carga de " + sourceCode + " leyó " + rowsRead
              + " fila(s) y ninguna fue válida: una lista vacía no se activa");
        this.sourceCode = sourceCode;
        this.rowsRead   = rowsRead;
    }

    public String sourceCode() { return sourceCode; }
    public int rowsRead()      { return rowsRead; }
}
