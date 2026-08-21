package com.fintech.stp.infrastructure.adapter.out.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Envelope encryption con AES-256-GCM.
 *
 * <p>Cada llave privada se cifra con una DEK propia, y la DEK se cifra con la KEK. La KEK vive
 * fuera de la base de datos, así que un dump de Postgres no compromete ninguna llave. Rotar la KEK
 * es re-envolver las DEK, sin descifrar el material RSA.
 */
final class EnvelopeCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final SecureRandom random = new SecureRandom();

    /** Ciphertext y tag de autenticación separados, como los guarda la tabla. */
    record Sealed(byte[] ciphertext, byte[] iv, byte[] authTag) {}

    SecretKey newDataKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
        generator.init(KEY_BITS, random);
        return generator.generateKey();
    }

    Sealed seal(byte[] plaintext, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
        byte[] combined = cipher.doFinal(plaintext);
        // GCM devuelve ciphertext||tag concatenados; se separan para que la tabla sea explícita.
        int split = combined.length - TAG_BYTES;
        return new Sealed(
                Arrays.copyOfRange(combined, 0, split),
                iv,
                Arrays.copyOfRange(combined, split, combined.length));
    }

    byte[] unseal(byte[] ciphertext, byte[] authTag, byte[] iv, byte[] key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
        byte[] combined = new byte[ciphertext.length + authTag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(authTag, 0, combined, ciphertext.length, authTag.length);
        return cipher.doFinal(combined);
    }
}
