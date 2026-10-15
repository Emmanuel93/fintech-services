package com.fintech.compliance.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * La persona a la que se le busca en las listas.
 *
 * <p>Se pasa el nombre **crudo** y no normalizado a propósito: normalizar es del dominio, y que lo
 * haga el llamador significaría que dos llamadores con distinto código obtienen distinto resultado
 * para la misma persona.
 */
public record ScreeningSubject(
        UUID partyId,
        String fullName,
        LocalDate dateOfBirth,
        String curp,
        String rfc,
        String country) {

    public NormalizedName normalized() { return NormalizedName.of(fullName); }

    /**
     * Las raíces de CURP con las que se busca a esta persona.
     *
     * <p>Son dos cosas a la vez: la raíz de su <b>CURP real</b> —que es la buena, y la que el
     * usuario pidió que siempre se consultara— y las <b>derivadas de su nombre y fecha</b>, que
     * sirven cuando la persona no trae CURP y además absorben las variantes de escritura de su
     * propio nombre. Se buscan juntas porque una entrada de lista sin CURP sólo tiene raíces
     * derivadas, y la comparación tiene que ocurrir en ese terreno.
     */
    public java.util.Set<String> curpRoots() {
        var out = new java.util.LinkedHashSet<String>();
        String propia = CurpRoot.of(curp);
        if (propia != null) out.add(propia);
        out.addAll(CurpRoot.candidatesFor(normalized(), dateOfBirth));
        return out;
    }

    public boolean hasDocument() {
        return (curp != null && !curp.isBlank()) || (rfc != null && !rfc.isBlank());
    }
}
