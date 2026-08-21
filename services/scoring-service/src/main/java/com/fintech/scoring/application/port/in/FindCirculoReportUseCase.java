package com.fintech.scoring.application.port.in;

import com.fintech.scoring.domain.CirculoReport;

import java.util.Optional;
import java.util.UUID;

/**
 * Lectura del reporte de buró para la mesa de análisis del backoffice.
 *
 * <p>Es el expediente crediticio que devolvió el buró (Círculo de Crédito): el detalle que el
 * analista revisa junto con el score y lo que capturó el sujeto. Dato sensible; su exposición al
 * usuario final se restringe a roles con facultad de análisis, decisión que toma el BFF.
 */
public interface FindCirculoReportUseCase {

    Optional<CirculoReport> findByProspectId(UUID prospectId);
}
