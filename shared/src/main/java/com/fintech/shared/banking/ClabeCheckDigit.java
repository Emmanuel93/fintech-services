package com.fintech.shared.banking;

/**
 * El dígito verificador de una CLABE, en un solo sitio.
 *
 * <p><b>Estaba escrito tres veces</b> —{@code stp}, {@code disbursement} y {@code banking}— con el
 * mismo algoritmo de Banxico y tres redacciones distintas del mismo bucle. No es una duplicación
 * inocente: el día que alguien corrija un caso borde en una copia, las otras dos seguirán
 * aceptando lo que aquélla rechaza, y el síntoma será que el mismo número pasa en un servicio y
 * rebota en otro.
 *
 * <p>El algoritmo: los primeros 17 dígitos se ponderan con la serie {@code 3,7,1} cíclica, cada
 * producto se toma módulo 10, se suman, y el verificador es lo que falta para la siguiente decena.
 *
 * <p><b>Java puro, sin Spring.</b> Vive en {@code shared} y lo usan servicios que no comparten nada
 * más; atarlo a un contexto lo volvería imposible de probar en aislamiento.
 */
public final class ClabeCheckDigit {

    public static final int LONGITUD = 18;
    private static final int CUERPO = 17;
    private static final int[] PESOS = {3, 7, 1};

    private ClabeCheckDigit() {}

    /** El verificador que le corresponde a los 17 primeros dígitos. */
    public static int de(String primeros17) {
        if (primeros17 == null || primeros17.length() != CUERPO
                || !primeros17.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Se esperan exactamente " + CUERPO + " dígitos para calcular el verificador");
        }
        int suma = 0;
        for (int i = 0; i < CUERPO; i++) {
            suma += ((primeros17.charAt(i) - '0') * PESOS[i % 3]) % 10;
        }
        return (10 - (suma % 10)) % 10;
    }

    /**
     * Si la CLABE completa es válida.
     *
     * <p>Devuelve {@code false} en vez de lanzar para lo mal formado: quien valida una cuenta de
     * beneficiario lo hace sobre un dato que llega de fuera, y una excepción convertiría un rechazo
     * previsible en un 500.
     */
    public static boolean esValida(String clabe) {
        if (clabe == null || clabe.length() != LONGITUD
                || !clabe.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return de(clabe.substring(0, CUERPO)) == (clabe.charAt(CUERPO) - '0');
    }

    /** Completa una CLABE de 17 dígitos. Útil para sembrar datos que no se pudren. */
    public static String completar(String primeros17) {
        return primeros17 + de(primeros17);
    }

    /**
     * Los tres primeros dígitos son la institución.
     *
     * <p>Devuelve {@code null} en vez de lanzar cuando la cuenta no es numérica: se invoca también
     * para rails que no son SPEI, donde la cuenta puede ser un número de teléfono o una tarjeta.
     * Lanzar aquí convertiría un dato opcional en un HTTP 500.
     */
    public static Integer institucionDe(String clabe) {
        if (clabe == null || clabe.length() < 3) {
            return null;
        }
        String prefijo = clabe.substring(0, 3);
        return prefijo.chars().allMatch(Character::isDigit) ? Integer.valueOf(prefijo) : null;
    }
}
