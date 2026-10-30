package com.fintech.beneficiary.domain;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lo que una CURP dice de su titular sin preguntarle nada.
 *
 * <p>La posición 11 es el sexo registral y la 12-13 la entidad de nacimiento. Origination pide
 * ambos datos para el prospecto, y pedírselos a ella en el formulario sería pedirle dos veces lo
 * mismo con el riesgo de que no coincidan: la CURP es la fuente, el formulario no.
 */
public final class Curp {

    /** El mismo patrón que valida origination en {@code RegisterProspectRequest}. */
    public static final Pattern PATTERN = Pattern.compile("^[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$");

    /**
     * Las 32 entidades más {@code NE} —nacida en el extranjero—, con el nombre como lo espera
     * origination en {@code stateOfBirth}.
     */
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("AS", "Aguascalientes"),
            Map.entry("BC", "Baja California"),
            Map.entry("BS", "Baja California Sur"),
            Map.entry("CC", "Campeche"),
            Map.entry("CL", "Coahuila"),
            Map.entry("CM", "Colima"),
            Map.entry("CS", "Chiapas"),
            Map.entry("CH", "Chihuahua"),
            Map.entry("DF", "Ciudad de México"),
            Map.entry("DG", "Durango"),
            Map.entry("GT", "Guanajuato"),
            Map.entry("GR", "Guerrero"),
            Map.entry("HG", "Hidalgo"),
            Map.entry("JC", "Jalisco"),
            Map.entry("MC", "Estado de México"),
            Map.entry("MN", "Michoacán"),
            Map.entry("MS", "Morelos"),
            Map.entry("NT", "Nayarit"),
            Map.entry("NL", "Nuevo León"),
            Map.entry("OC", "Oaxaca"),
            Map.entry("PL", "Puebla"),
            Map.entry("QT", "Querétaro"),
            Map.entry("QR", "Quintana Roo"),
            Map.entry("SP", "San Luis Potosí"),
            Map.entry("SL", "Sinaloa"),
            Map.entry("SR", "Sonora"),
            Map.entry("TC", "Tabasco"),
            Map.entry("TS", "Tamaulipas"),
            Map.entry("TL", "Tlaxcala"),
            Map.entry("VZ", "Veracruz"),
            Map.entry("YN", "Yucatán"),
            Map.entry("ZS", "Zacatecas"),
            Map.entry("NE", "Nacido en el extranjero"));

    private Curp() {}

    public static boolean isValid(String curp) {
        return curp != null && PATTERN.matcher(curp).matches();
    }

    /** {@code MALE} | {@code FEMALE}, como lo espera el enum {@code Gender} de origination. */
    public static String genderOf(String curp) {
        requireValid(curp);
        return curp.charAt(10) == 'H' ? "MALE" : "FEMALE";
    }

    /** La entidad de nacimiento, o vacío si el código no es uno de los 33. */
    public static Optional<String> stateOfBirthOf(String curp) {
        requireValid(curp);
        return Optional.ofNullable(STATES.get(curp.substring(11, 13)));
    }

    public static boolean isKnownStateCode(String code) {
        return STATES.containsKey(code);
    }

    private static void requireValid(String curp) {
        if (!isValid(curp)) throw new PlacementValidationException("CURP inválida: " + curp);
    }
}
