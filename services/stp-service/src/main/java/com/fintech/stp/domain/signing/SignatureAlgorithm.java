package com.fintech.stp.domain.signing;

/**
 * Algoritmo de firma acordado con STP. Es constante del protocolo, no configuración: cambiarlo
 * invalida todas las órdenes. Se declara como enum para que aparezca en los metadatos de la llave
 * y quede registrado con qué se firmó cada orden.
 */
public enum SignatureAlgorithm {

    SHA256_WITH_RSA("SHA256withRSA");

    private final String jcaName;

    SignatureAlgorithm(String jcaName) {
        this.jcaName = jcaName;
    }

    /** Nombre JCA, el que espera {@code java.security.Signature#getInstance(String)}. */
    public String jcaName() {
        return jcaName;
    }
}
