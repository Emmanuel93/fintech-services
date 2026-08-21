package com.fintech.stp.domain;

import com.fintech.stp.domain.signing.OrdenPagoFirma;

/**
 * Puente mínimo para que el agregado aplique la misma regla de truncado que usa la firma, sin
 * duplicar la constante ni acoplar la entidad al builder.
 */
final class OrdenPagoFirmaSupport {

    private OrdenPagoFirmaSupport() {
    }

    static String truncate(String beneficiaryName) {
        return OrdenPagoFirma.truncarNombreBeneficiario(beneficiaryName);
    }
}
