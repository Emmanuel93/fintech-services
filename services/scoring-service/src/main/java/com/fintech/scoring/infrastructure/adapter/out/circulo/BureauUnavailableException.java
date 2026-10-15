package com.fintech.scoring.infrastructure.adapter.out.circulo;

/**
 * El buró no contestó: timeout, caída o red.
 *
 * <p>No extiende {@code IOException} porque el puerto {@code CirculoGateway} no declara excepciones
 * comprobadas, y añadírselas obligaría a cada llamador a acordarse de ellas. El prefetch ya atrapa
 * {@code Exception} y marca {@code FAILED}, que es exactamente el camino que este escenario existe
 * para ejercitar.
 */
public class BureauUnavailableException extends RuntimeException {
    public BureauUnavailableException(String message) { super(message); }
}
