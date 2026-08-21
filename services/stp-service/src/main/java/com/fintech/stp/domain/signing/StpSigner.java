package com.fintech.stp.domain.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Firma y verificación sobre la cadena original. Java puro: sin Spring, sin acceso a base de datos,
 * sin logging. Recibe la llave ya resuelta y devuelve la firma en Base64.
 *
 * <p>Que no dependa de nada es deliberado. Es la pieza de mayor riesgo del servicio (una diferencia
 * de un byte y STP rechaza todo), así que tiene que poder probarse con un {@code main} y sin
 * levantar un contexto.
 *
 * <p>Quién resuelve la llave, dónde vive y cómo se cachea es problema del puerto
 * {@code SigningKeyProvider}, no de aquí.
 */
public final class StpSigner {

    private StpSigner() {
    }

    /** Firma la cadena en UTF-8 y devuelve Base64 estándar, sin saltos de línea. */
    public static String sign(String cadenaOriginal, PrivateKey privateKey, SignatureAlgorithm algorithm) {
        if (cadenaOriginal == null || cadenaOriginal.isBlank()) {
            throw new StpSignatureException("La cadena original no puede estar vacía");
        }
        if (privateKey == null) {
            throw new StpSignatureException("No hay llave privada disponible para firmar");
        }
        try {
            Signature signature = Signature.getInstance(algorithm.jcaName());
            signature.initSign(privateKey);
            signature.update(cadenaOriginal.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            // El mensaje NO incluye la cadena original: el legado la logueaba en nivel SEVERE.
            throw new StpSignatureException("No se pudo firmar la cadena original", e);
        }
    }

    /** Verifica un sello recibido. Devuelve {@code false} en vez de lanzar: un sello inválido es un
     *  resultado de negocio esperado (regla SO-04), no una excepción. */
    public static boolean verify(String cadenaOriginal, String selloBase64,
                                 PublicKey publicKey, SignatureAlgorithm algorithm) {
        if (cadenaOriginal == null || selloBase64 == null || selloBase64.isBlank() || publicKey == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(algorithm.jcaName());
            signature.initVerify(publicKey);
            signature.update(cadenaOriginal.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(selloBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
