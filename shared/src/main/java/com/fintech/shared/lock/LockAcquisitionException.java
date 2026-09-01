package com.fintech.shared.lock;

/**
 * El candado no se pudo consultar. <b>No</b> es "otro lo tiene" —eso se expresa devolviendo vacío—,
 * sino que la infraestructura de exclusión no está disponible.
 *
 * <p>Se propaga a propósito y no se degrada a "seguir sin candado": en un proceso financiero, correr
 * sin exclusión es peor que no correr.
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(LockKey key, Throwable cause) {
        super("No se pudo operar el candado " + key.value() + ": " + cause.getMessage(), cause);
    }
}
