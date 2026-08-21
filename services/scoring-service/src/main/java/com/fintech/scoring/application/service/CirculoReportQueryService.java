package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.in.FindCirculoReportUseCase;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.domain.CirculoReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Lectura del reporte de buró más reciente de un prospecto.
 *
 * <p>El reporte cuelga de cinco colecciones perezosas (créditos, domicilios, empleos, consultas,
 * scores). La consulta se resuelve dentro de una transacción de solo lectura y se inicializan las
 * colecciones antes de devolver el agregado, para que el mapeo a DTO en el controlador no tope con
 * una {@code LazyInitializationException} fuera de la sesión.
 *
 * <p><b>Por qué se recorren y no se llama {@code Hibernate.initialize}:</b> los getters del
 * agregado devuelven {@code Collections.unmodifiableList(...)}, que es un envoltorio y no la
 * colección persistente. {@code Hibernate.initialize} sobre ese envoltorio no hace nada —no es un
 * proxy— así que la carga parecía resuelta y el fallo aparecía después, ya fuera de la sesión, en
 * el mapeo a DTO. Leer el tamaño sí toca la colección de abajo y la trae.
 */
@Service
public class CirculoReportQueryService implements FindCirculoReportUseCase {

    private final CirculoReportRepository reportRepository;

    public CirculoReportQueryService(CirculoReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CirculoReport> findByProspectId(UUID prospectId) {
        Optional<CirculoReport> report = reportRepository.findByProspectId(prospectId);
        report.ifPresent(r -> {
            r.getCredits().size();
            r.getAddresses().size();
            r.getEmployments().size();
            r.getInquiries().size();
            r.getScores().size();
        });
        return report;
    }
}
