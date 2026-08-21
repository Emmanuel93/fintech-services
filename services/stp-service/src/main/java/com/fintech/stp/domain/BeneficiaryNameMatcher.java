package com.fintech.stp.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Compara el nombre del beneficiario que enviamos contra el que devuelve el CEP.
 *
 * <p>No es igualdad de cadenas: los bancos receptores devuelven el nombre con acentos quitados,
 * comas, diagonales y espacios distintos. La normalización descompone en NFD, elimina lo que no es
 * ASCII y se queda sólo con letras.
 *
 * <p>Casos reales que el legado ya cubría y que deben seguir dando {@code true}:
 * {@code "NIKTE-HA DE GUADALUPE OROPEZA VAZQUEZ"} vs {@code "NIKTE HA DE GUADALUPE,OROPEZA/VAZQUEZ"}.
 *
 * <p>Un {@code false} no bloquea la liquidación — el dinero ya se movió. Se propaga como bandera
 * para que operación lo revise.
 */
public final class BeneficiaryNameMatcher {

    private BeneficiaryNameMatcher() {
    }

    public static boolean matches(String nameOne, String nameTwo) {
        if (nameOne == null || nameTwo == null) {
            return false;
        }
        return normalize(nameOne).equalsIgnoreCase(normalize(nameTwo));
    }

    /**
     * Compara sabiendo que lo enviado pudo truncarse a 40 caracteres por límite de STP: en ese caso
     * basta con que el nombre del CEP empiece igual. Sin esto, todo nombre largo daría
     * {@code false} — que es exactamente lo que le pasaba al legado.
     */
    public static boolean matchesAllowingTruncation(String sentName, String cepName, int sentMaxLength) {
        if (sentName == null || cepName == null) {
            return false;
        }
        String sent = normalize(sentName);
        String cep = normalize(cepName);
        if (sent.equalsIgnoreCase(cep)) {
            return true;
        }
        boolean wasTruncated = sentName.length() >= sentMaxLength;
        return wasTruncated && !sent.isEmpty() && cep.toUpperCase(Locale.ROOT).startsWith(sent.toUpperCase(Locale.ROOT));
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .replaceAll("[^A-Za-z]", "");
    }
}
