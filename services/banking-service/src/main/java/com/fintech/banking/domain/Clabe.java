package com.fintech.banking.domain;

/**
 * La CLABE, con su dígito verificador comprobado.
 *
 * <p>No es validación decorativa. Una CLABE mal capturada en una <b>cuenta propia</b> no rebota en
 * el momento del alta: rebota el día del primer pago, cuando la orden ya salió y el dinero está en
 * tránsito hacia una cuenta que no es nuestra. El dígito verificador atrapa el error de captura más
 * común —un dígito cambiado o dos transpuestos— en el único momento en que corregirlo es gratis.
 *
 * <p>El algoritmo vive en {@code shared.banking.ClabeCheckDigit} (BK-49). Lo que esta clase añade es
 * lo que un validador estático no puede: enmascarar, extraer la institución y <b>negarse a existir</b>
 * si el número no es válido. Separar esas tres cosas es lo que deja que una CLABE cruda llegue a una
 * bitácora.
 */
public final class Clabe {

    private static final int LONGITUD = com.fintech.shared.banking.ClabeCheckDigit.LONGITUD;

    private final String valor;

    private Clabe(String valor) { this.valor = valor; }

    public static Clabe of(String entrada) {
        if (entrada == null) {
            throw new IllegalArgumentException("CLABE requerida");
        }
        String limpia = entrada.replaceAll("[\\s-]", "");
        if (limpia.length() != LONGITUD || !limpia.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("CLABE debe tener 18 dígitos: " + entrada);
        }
        // El CUERPO, no la CLABE entera. La versión local aceptaba callada los 18 caracteres y
        // leía sólo los primeros 17; la compartida exige el cuerpo exacto, y tiene razón: pasarle
        // la cadena completa oculta un malentendido sobre qué recibe.
        int esperado = digitoVerificador(limpia.substring(0, LONGITUD - 1));
        int recibido = limpia.charAt(17) - '0';
        if (esperado != recibido) {
            // El mensaje NO revela el dígito correcto: quien captura debe volver al documento
            // fuente, no ajustar el último dígito hasta que la validación calle.
            throw new IllegalArgumentException("CLABE con dígito verificador inválido: " + limpia);
        }
        return new Clabe(limpia);
    }

    /**
     * El verificador que le corresponde a los 17 primeros dígitos. Público porque el sembrado y las
     * pruebas necesitan construir CLABEs válidas, y una constante escrita a mano se pudre en cuanto
     * alguien cambia un dígito del cuerpo.
     *
     * <p>Delega en {@code shared} (BK-49): el algoritmo estaba escrito tres veces.
     */
    public static int digitoVerificador(String clabe) {
        return com.fintech.shared.banking.ClabeCheckDigit.de(clabe);
    }

    /** Los tres primeros dígitos son la institución; el mismo dato que STP llama banco receptor. */
    public String institucion() { return valor.substring(0, 3); }

    public String valor() { return valor; }

    /** Para bitácora y pantalla: nunca se registra la CLABE completa. */
    public String enmascarada() { return "****" + valor.substring(14); }

    @Override public String toString() { return enmascarada(); }
    @Override public boolean equals(Object o) {
        return o instanceof Clabe c && valor.equals(c.valor);
    }
    @Override public int hashCode() { return valor.hashCode(); }
}
