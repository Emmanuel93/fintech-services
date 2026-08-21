package com.fintech.scoring.infrastructure.adapter.out.circulo;

import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adaptador de buró SOLO para ambientes dev/local ({@code fintech.circulo.mock-enabled=true}).
 * Sustituye la llamada real a Círculo de Crédito por un reporte DETERMINISTA derivado del CURP,
 * para poder correr el journey end-to-end sin depender del sandbox externo (que rechaza CURPs
 * sintéticos con 400). <b>Nunca debe activarse en producción.</b>
 *
 * <p>Convención de demo:
 * <ul>
 *   <li>CURP placeholder {@code XEXX...} → FICO 480 → cae en la banda de RECHAZO.</li>
 *   <li>Cualquier otro CURP → FICO alto (720–819), historial limpio → aprueba
 *       (B2B/B2B2C caen a comité por su política de scoring, no por el reporte).</li>
 * </ul>
 * El historial limpio (sin créditos ni consultas) evita descalificación por mora, así que
 * la decisión la determina puramente la banda de FICO de la política.
 */
@Component
@ConditionalOnProperty(name = "fintech.circulo.mock-enabled", havingValue = "true")
class MockCirculoAdapter implements CirculoGateway {

    private static final Logger log = LoggerFactory.getLogger(MockCirculoAdapter.class);

    @Override
    public CirculoReport query(UUID reportId, UUID prefetchId, UUID prospectId, CirculoQueryRequest req) {
        String curp = req.curp() != null ? req.curp().toUpperCase() : "";
        boolean forceReject = curp.startsWith("XEXX");
        // 720..819 para CURPs normales (>=700 → banda AUTO_APPROVED en la política B2C).
        int fico = forceReject ? 480 : 720 + Math.floorMod(curp.hashCode(), 100);

        log.warn("[MOCK BURÓ] Reporte determinista prospectId={} curp={} fico={} — fintech.circulo.mock-enabled=true, NO usar en prod",
                prospectId, curp, fico);

        return CirculoReport.builder(reportId, prefetchId, prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .folioConsulta("MOCK-" + reportId.toString().substring(0, 8))
                .claveOtorgante("MOCK")
                .personaNombres(req.primerNombre())
                .personaApellidoPaterno(req.apellidoPaterno())
                .personaApellidoMaterno(req.apellidoMaterno())
                .personaFechaNacimiento(req.fechaNacimiento())
                .personaRfc(req.rfc())
                .personaCurp(curp)
                .personaNacionalidad("MX")
                .ficoScoreValor(fico)
                .ficoScoreRazones(forceReject ? "MOCK-REJECT" : "MOCK-CLEAN")
                .build();
        // Historial vacío a propósito: sin créditos → sin mora → la decisión la fija la banda FICO.
    }
}
