package com.fintech.stp.infrastructure.adapter.out.crypto;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Locale;

/**
 * Resuelve la KEK desde el entorno.
 *
 * <p>KY-02: la KEK nunca se persiste en Postgres ni se versiona. Viene de una variable de entorno
 * o de un Secret de Kubernetes montado como variable. Migrar a Azure Key Vault o a un KMS es
 * cambiar sólo esta clase.
 *
 * <p>El {@code kekId} viaja en cada fila de {@code company_keys}, así que puede haber varias KEK
 * vivas a la vez y rotarlas sin descifrar material RSA.
 */
@Component
public class KeyEncryptionKeyResolver {

    static final String CURRENT_KEK_PROPERTY = "fintech.stp.crypto.current-kek-id";
    private static final String KEK_PREFIX = "STP_KEK_";
    private static final int EXPECTED_LENGTH_BYTES = 32;

    private final Environment environment;

    public KeyEncryptionKeyResolver(Environment environment) {
        this.environment = environment;
    }

    /** Identificador de la KEK con la que se envuelve lo nuevo. */
    public String currentKekId() {
        String kekId = environment.getProperty(CURRENT_KEK_PROPERTY);
        if (kekId == null || kekId.isBlank()) {
            throw new IllegalStateException(
                    "No hay KEK configurada. Define " + CURRENT_KEK_PROPERTY + " y la variable "
                            + KEK_PREFIX + "<id> con 32 bytes en Base64.");
        }
        return kekId;
    }

    /** Resuelve el material de una KEK por su id. Nunca se loguea ni se cachea en disco. */
    public byte[] resolve(String kekId) {
        String variable = KEK_PREFIX + kekId.toUpperCase(Locale.ROOT).replace('-', '_');
        String encoded = environment.getProperty(variable);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("No se encontró la KEK '" + kekId + "' (variable " + variable + ")");
        }
        byte[] material = Base64.getDecoder().decode(encoded.trim());
        if (material.length != EXPECTED_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "La KEK '" + kekId + "' debe tener exactamente " + EXPECTED_LENGTH_BYTES
                            + " bytes (AES-256); tiene " + material.length);
        }
        return material;
    }
}
