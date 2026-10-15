package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.ScreeningCheck;

import java.util.List;
import java.util.UUID;

/**
 * La bitácora de consultas a listas. <b>Sólo se añade y se lee.</b>
 *
 * <p>No hay {@code update} ni {@code delete} a propósito, y no es una omisión que alguien deba
 * completar después: un registro de auditoría que se puede editar no sustenta nada. La retención la
 * fija la LFPIORPI en <b>diez años</b> para registros de AML.
 */
public interface ScreeningCheckRepository {

    ScreeningCheck save(ScreeningCheck check);

    /** El historial de una persona, lo más reciente primero. Es lo que mira quien audita. */
    List<ScreeningCheck> findByProspectId(UUID prospectId, int limit);

    long countByProspectId(UUID prospectId);
}
