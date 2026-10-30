package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.domain.Curp;
import com.fintech.beneficiary.domain.PublicKycException;
import com.fintech.shared.banking.ClabeCheckDigit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Valida «Tus datos» y devuelve <b>todos</b> los errores a la vez.
 *
 * <p>Uno por uno obligaría a la beneficiaria a enviar, leer, corregir y volver a enviar tantas
 * veces como campos malos tenga — desde un teléfono, con la sesión de 15 minutos corriendo.
 *
 * <p>Las reglas son las de origination (CURP, RFC, código postal) más la CLABE con su dígito
 * verificador Banxico: un dígito mal tecleado no lo detecta la forma de 18 dígitos, lo detecta el
 * verificador — y sin él, el SPEI rebotaría días después, con la colocación ya aprobada.
 */
public class KycDataValidator {

    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");

    /** Persona física: 4 letras, fecha, homoclave. 13 posiciones. */
    private static final Pattern RFC = Pattern.compile("^[A-Z&Ñ]{4}\\d{6}[A-Z0-9]{3}$");
    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Clock clock;

    public KycDataValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return los datos normalizados —recortados, CURP y RFC en mayúsculas—, que es lo que se
     *         guarda: la sesión no debe conservar una versión que origination rechazaría
     * @throws PublicKycException.InvalidData con cada campo malo y su motivo
     */
    public KycData validate(KycData raw) {
        if (raw == null) {
            throw new PublicKycException.InvalidData(Map.of("body", "Faltan los datos"));
        }
        Map<String, String> errors = new LinkedHashMap<>();

        String firstName      = required(errors, "firstName", raw.firstName());
        String lastName1      = required(errors, "lastName1", raw.lastName1());
        String lastName2      = trimToNull(raw.lastName2());
        String curp           = upper(required(errors, "curp", raw.curp()));
        String rfc            = upper(required(errors, "rfc", raw.rfc()));
        String dateOfBirth    = required(errors, "dateOfBirth", raw.dateOfBirth());
        String occupation     = required(errors, "occupation", raw.occupation());
        String street         = required(errors, "street", raw.street());
        String exteriorNumber = required(errors, "exteriorNumber", raw.exteriorNumber());
        String neighborhood   = required(errors, "neighborhood", raw.neighborhood());
        String municipality   = required(errors, "municipality", raw.municipality());
        String city           = required(errors, "city", raw.city());
        String state          = required(errors, "state", raw.state());
        String postalCode     = required(errors, "postalCode", raw.postalCode());
        String clabe          = required(errors, "clabe", raw.clabe());
        String email          = trimToNull(raw.email());

        if (curp != null) {
            if (!Curp.isValid(curp)) {
                errors.put("curp", "La CURP no tiene un formato válido");
            } else if (!Curp.isKnownStateCode(curp.substring(11, 13))) {
                errors.put("curp", "La entidad de nacimiento de la CURP no existe");
            }
        }
        if (rfc != null && !RFC.matcher(rfc).matches()) {
            errors.put("rfc", "El RFC debe tener 13 caracteres: 4 letras, 6 dígitos y homoclave");
        }
        if (dateOfBirth != null) {
            try {
                LocalDate dob = LocalDate.parse(dateOfBirth);
                LocalDate today = LocalDate.now(clock.withZone(MEXICO));
                if (!dob.isBefore(today)) {
                    errors.put("dateOfBirth", "La fecha de nacimiento debe ser pasada");
                } else if (Period.between(dob, today).getYears() < 18) {
                    // Un contrato de crédito no lo firma una menor de edad, y su consentimiento de
                    // buró no tendría valor legal.
                    errors.put("dateOfBirth", "Debes ser mayor de edad");
                }
            } catch (DateTimeParseException e) {
                errors.put("dateOfBirth", "La fecha debe venir como AAAA-MM-DD");
            }
        }
        BigDecimal monthlyIncome = raw.monthlyIncome();
        if (monthlyIncome == null) {
            errors.put("monthlyIncome", "Es obligatorio");
        } else if (monthlyIncome.signum() <= 0) {
            errors.put("monthlyIncome", "Debe ser mayor a cero");
        }
        if (postalCode != null && !POSTAL_CODE.matcher(postalCode).matches()) {
            errors.put("postalCode", "El código postal es de 5 dígitos");
        }
        if (clabe != null) {
            String digits = clabe.replace(" ", "");
            if (!digits.matches("\\d{18}")) {
                errors.put("clabe", "La CLABE es de 18 dígitos");
            } else if (!ClabeCheckDigit.isValid(digits)) {
                errors.put("clabe", "La CLABE no es válida: revisa que no falte ni sobre un dígito");
            } else {
                clabe = digits;
            }
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            errors.put("email", "El correo no tiene un formato válido");
        }

        if (!errors.isEmpty()) {
            throw new PublicKycException.InvalidData(errors);
        }
        return new KycData(firstName, lastName1, lastName2, curp, rfc, dateOfBirth, occupation,
                monthlyIncome, street, exteriorNumber, neighborhood, municipality, city, state,
                postalCode, clabe, email);
    }

    private static String required(Map<String, String> errors, String field, String value) {
        String v = trimToNull(value);
        if (v == null) errors.put(field, "Es obligatorio");
        return v;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
