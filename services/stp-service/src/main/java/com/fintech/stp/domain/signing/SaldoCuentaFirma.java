package com.fintech.stp.domain.signing;

import java.time.LocalDate;

/**
 * Bean firmable de la consulta de saldo. Tres componentes, en este orden exacto.
 * Un {@code null} en cualquiera de ellos se rinde como cadena vacía — ver {@link CadenaOriginalBuilder}.
 */
public record SaldoCuentaFirma(
        String empresa,          // 1
        String cuentaOrdenante,  // 2
        LocalDate fecha          // 3
) {}
