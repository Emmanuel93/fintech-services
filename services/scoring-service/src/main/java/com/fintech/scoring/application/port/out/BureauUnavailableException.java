package com.fintech.scoring.application.port.out;

/**
 * El buró no contestó: timeout, caída o red.
 *
 * <p>Es la falla <b>transitoria</b>: el prefetch la reintenta con backoff y, agotados los
 * reintentos, deja el reporte en {@code ERROR} y el prefetch en {@code FAILED} con el motivo.
 *
 * <p>No extiende {@code IOException} porque el puerto {@code CirculoGateway} no declara excepciones
 * comprobadas, y añadírselas obligaría a cada llamador a acordarse de ellas.
 */
public class BureauUnavailableException extends RuntimeException {
    public BureauUnavailableException(String message) { super(message); }
    public BureauUnavailableException(String message, Throwable cause) { super(message, cause); }
}
