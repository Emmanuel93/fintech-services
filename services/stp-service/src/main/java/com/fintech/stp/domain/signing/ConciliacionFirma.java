package com.fintech.stp.domain.signing;

import java.time.LocalDate;

/**
 * Bean firmable de la consulta de conciliación. Tres componentes, en este orden exacto.
 *
 * <p>En el legado esta firma sólo se usaba en el batch nocturno. Aquí es camino crítico: el poller
 * de liquidación (que sustituye a los webhooks) firma una de estas en cada corrida.
 *
 * @param tipoOrden {@code "E"} enviadas · {@code "R"} recibidas
 */
public record ConciliacionFirma(
        String empresa,           // 1
        String tipoOrden,         // 2
        LocalDate fechaOperacion  // 3
) {}
