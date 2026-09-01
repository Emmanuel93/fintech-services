package com.fintech.creditportfolio.application.port.in;

import com.fintech.creditportfolio.domain.relief.ReliefProgram;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Alta, autorización y otorgamiento masivo de un programa de apoyo.
 *
 * <p>El ciclo es maker-checker, como en {@code configuration-service}: se propone, alguien distinto
 * autoriza, y sólo entonces se puede otorgar. Un programa mueve la fecha de pago de una cartera
 * entera y sube la reserva — que una sola persona pueda hacerlo sin contraparte es el tipo de
 * facultad que una revisión pregunta primero.
 */
public interface ManageReliefProgramUseCase {

    record Proponer(String name, String reason, int deferredPeriods,
                    LocalDate validFrom, LocalDate validTo,
                    String productCode, String originUnitCode, String region,
                    Integer maxDaysDelinquent, LocalDate eligibilityCutoffDate,
                    String accrualDuringRelief) {}

    /** Cuántas cuentas alcanzaría, sin tocar ninguna. Se consulta antes de autorizar. */
    /**
     * A cuántas cuentas alcanzaría el programa, y a cuántas <b>no</b> por estar ya apoyadas.
     *
     * <p>{@code yaApoyadas} no es un detalle de implementación: quien autoriza un apoyo masivo
     * necesita saber que hay cartera que ya viene de otro programa. Sin esa cifra, dos programas
     * que se solapan se ven cada uno razonable y juntos difieren el doble de lo autorizado.
     */
    record Padron(UUID programId, int cuentasElegibles, int yaApoyadas, List<UUID> muestra) {}

    ReliefProgram proponer(Proponer alta, String proposedBy);

    ReliefProgram autorizar(UUID programId, String approvedBy);

    Padron simularPadron(UUID programId);

    /** @return cuántas cuentas quedaron inscritas */
    int otorgar(UUID programId);
}
