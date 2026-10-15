package com.fintech.compliance.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que cumplimiento necesita de {@code origination.prospect-created}: quién es la persona.
 *
 * <p>Se declara sólo eso y se ignora el resto —canal, domicilio, consentimientos, documentos— a
 * propósito: copiar aquí campos que no se usan es mantener una segunda versión de algo ajeno que se
 * desincroniza sin que nadie lo note.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectCreatedPayload(
        UUID prospectId,
        String firstName,
        String lastName1,
        String lastName2,
        String curp,
        String rfc,
        LocalDate dateOfBirth) {

    /**
     * El nombre completo, tal como se buscará en las listas.
     *
     * <p>Se arma aquí y no se normaliza: normalizar es del dominio. Si cada llamador normalizara por
     * su cuenta, dos servicios con la misma persona obtendrían distinto resultado.
     */
    public String fullName() {
        return java.util.stream.Stream.of(firstName, lastName1, lastName2)
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " " + b).orElse("");
    }
}
