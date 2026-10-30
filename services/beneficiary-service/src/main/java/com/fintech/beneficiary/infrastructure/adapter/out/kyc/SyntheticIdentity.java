package com.fintech.beneficiary.infrastructure.adapter.out.kyc;

/**
 * Una identidad verosímil derivada del nombre que tecleó el distribuidor.
 *
 * <p>La usan los dos sitios que hoy no tienen de dónde leer una INE de verdad: el simulador de KYC
 * ({@code KycSimulationController}) y el OCR stub ({@link StubIneReader}). Vive en un solo lugar
 * para que ambos produzcan <b>exactamente</b> la misma CURP para la misma persona: si divergieran,
 * una beneficiaria que se simuló y otra que pasó por la web con el mismo nombre parecerían dos
 * personas distintas ante origination.
 *
 * <p>Nada de esto pretende ser la identidad real de nadie: tiene la <i>forma</i> que exige
 * origination y es estable entre reintentos, que es todo lo que un stub necesita.
 */
public final class SyntheticIdentity {

    /** La fecha que se asume cuando no hay de dónde leerla. Coincide con la de la CURP sintética. */
    public static final String DATE_OF_BIRTH = "1990-06-12";

    // El domicilio de arranque: una dirección real de la CDMX con forma completa, para que el
    // formulario precargado pase la validación de origination sin que nadie lo toque.
    public static final String STREET          = "Cerrada de Xochicalco";
    public static final String EXTERIOR_NUMBER = "44";
    public static final String NEIGHBORHOOD    = "Narvarte Poniente";
    public static final String MUNICIPALITY    = "Benito Juárez";
    public static final String CITY            = "Ciudad de México";
    public static final String STATE           = "Ciudad de México";
    public static final String POSTAL_CODE     = "03020";

    private SyntheticIdentity() {}

    /**
     * Nombre(s), primer apellido, segundo apellido.
     *
     * <p>Con cuatro o más palabras se asume dos nombres y dos apellidos —«María Luisa Cortés
     * Hernández»—, que es el caso más común en México. No es infalible («María de la Luz») y no
     * tiene que serlo: ella corrige en «Tus datos».
     */
    public static String[] splitName(String full) {
        String[] parts = full == null ? new String[0] : full.trim().split("\\s+");
        return switch (parts.length) {
            case 0 -> new String[]{"Beneficiaria", "Sin", "Apellido"};
            case 1 -> new String[]{parts[0], "Sin", "Apellido"};
            case 2 -> new String[]{parts[0], parts[1], "Sin"};
            case 3 -> new String[]{parts[0], parts[1], parts[2]};
            default -> new String[]{parts[0] + " " + parts[1], parts[2], parts[3]};
        };
    }

    /**
     * CURP con la forma que exige origination; no pretende ser la real de nadie.
     *
     * <p>Se quitan acentos antes de armarla: «Díaz» produciría una Í y el patrón sólo acepta
     * A-Z. Es el mismo saneo que haría un OCR de verdad al leer la credencial.
     */
    public static String syntheticCurp(String[] names, String phone) {
        String paterno = ascii(names[1]);
        String materno = ascii(names[2]);
        String name  = ascii(names[0]);
        String initials = letter(paterno, 0) + vowel(paterno) + letter(materno, 0) + letter(name, 0);
        // AAAAdddddd H|M EE CCC d — 18 posiciones exactas.
        return (initials + "900612" + "MDF"
                + letter(paterno, 1) + letter(materno, 1) + letter(name, 1)
                + phone.charAt(8) + phone.charAt(9)).toUpperCase();
    }

    /**
     * RFC de persona física a partir de la CURP: sus diez primeras posiciones más homoclave.
     *
     * <p>Las tres de la homoclave las calcula el SAT; aquí se derivan de la propia CURP para que
     * sean estables entre reintentos — un RFC que cambia en cada llamada rompería la
     * idempotencia del alta.
     */
    public static String rfcFrom(String curp) {
        String base = curp.substring(0, 10);
        int hash = Math.abs(curp.hashCode());
        char[] alphabet = "ABCDEFGHIJKLMNPQRSTUVWXYZ".toCharArray();
        return base + alphabet[hash % alphabet.length]
                    + (hash / 10 % 10)
                    + alphabet[(hash / 100) % alphabet.length];
    }

    /** Sin acentos, sin ñ y sin nada que no sea una letra del alfabeto inglés. */
    static String ascii(String s) {
        if (s == null || s.isBlank()) return "X";
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("Ñ", "X").replace("ñ", "x")
                .replaceAll("[^A-Za-z]", "");
        return normalized.isBlank() ? "X" : normalized.toUpperCase();
    }

    /** La i-ésima letra, o X si no la hay. Ya viene saneada por {@link #ascii(String)}. */
    private static String letter(String s, int i) {
        return s != null && s.length() > i ? String.valueOf(s.charAt(i)).toUpperCase() : "X";
    }

    /** Primera vocal interna del apellido — la tercera posición de una CURP real. */
    private static String vowel(String s) {
        if (s == null || s.length() < 2) return "X";
        for (char c : s.substring(1).toCharArray()) {
            if ("AEIOU".indexOf(Character.toUpperCase(c)) >= 0) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "X";
    }
}
