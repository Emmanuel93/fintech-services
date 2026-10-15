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
 * <p><b>Se comporta como el buró, incluido fallar.</b> Un mock que sólo sabe contestar bien no
 * prueba la resiliencia: la esconde. Y el camino que más importa aquí es justamente el del buró que
 * no contesta, porque es el único sin salida humana — nadie puede inventar un puntaje.
 *
 * <p>El escenario se elige por el <b>prefijo del CURP</b>, que es el selector que este proveedor sí
 * tiene. STP usa los centavos del importe; aquí no hay importe, y el CURP viaja en cada consulta.
 *
 * <h4>Escenarios</h4>
 * <table>
 *   <tr><th>CURP empieza por</th><th>Qué pasa</th><th>Para qué</th></tr>
 *   <tr><td>{@code XEXX}</td><td>FICO 480, reporte válido</td><td>la banda de RECHAZO</td></tr>
 *   <tr><td>{@code ZZER}</td><td>{@code ERROR}</td><td>el buró contesta que no pudo</td></tr>
 *   <tr><td>{@code ZZNH}</td><td>{@code NO_HIT}</td><td>persona sin historial</td></tr>
 *   <tr><td>{@code ZZNF}</td><td>{@code NOT_FOUND}</td><td>el buró no la encuentra</td></tr>
 *   <tr><td>{@code ZZTO}</td><td><b>lanza</b></td><td>timeout o caída — el prefetch queda FAILED</td></tr>
 *   <tr><td>cualquier otro</td><td>FICO 720–819, historial limpio</td><td>el camino feliz</td></tr>
 * </table>
 *
 * <p>Los cuatro últimos son la diferencia entre un mock y un {@code Noop}: sin ellos, «qué pasa si
 * el buró no contesta» es una pregunta que sólo se responde en producción.
 *
 * <p>El historial limpio (sin créditos ni consultas) evita descalificación por mora, así que en el
 * camino feliz la decisión la determina puramente la banda de FICO de la política.
 */
@Component
@ConditionalOnProperty(name = "fintech.circulo.mock-enabled", havingValue = "true")
class MockCirculoAdapter implements CirculoGateway {

    private static final Logger log = LoggerFactory.getLogger(MockCirculoAdapter.class);

    @Override
    public CirculoReport query(UUID reportId, UUID prefetchId, UUID prospectId, CirculoQueryRequest req) {
        String curp = req.curp() != null ? req.curp().toUpperCase() : "";

        // Los escenarios de FALLO van primero: son los que no se pueden probar de otra forma.
        if (curp.startsWith("ZZTO")) {
            // Se lanza en vez de devolver un estado: es lo que hace un cliente HTTP cuando el
            // proveedor no contesta, y es el camino que deja el prefetch en FAILED.
            log.warn("[MOCK BURÓ] Escenario TIMEOUT para prospectId={} — el buró no contesta", prospectId);
            throw new BureauUnavailableException(
                    "Mock: Círculo no respondió en el tiempo previsto (CURP ZZTO)");
        }
        CirculoReportStatus fallo = failureScenario(curp);
        if (fallo != null) {
            log.warn("[MOCK BURÓ] Escenario {} para prospectId={} curp={}", fallo, prospectId, curp);
            return CirculoReport.builder(reportId, prefetchId, prospectId)
                    .status(fallo)
                    .errorMessage("Mock: escenario " + fallo + " seleccionado por el CURP")
                    .folioConsulta("MOCK-" + reportId.toString().substring(0, 8))
                    .claveOtorgante("MOCK")
                    .build();
        }

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

    /** El estado de fallo que pide el CURP, o {@code null} si toca camino normal. */
    private static CirculoReportStatus failureScenario(String curp) {
        if (curp.startsWith("ZZER")) return CirculoReportStatus.ERROR;
        if (curp.startsWith("ZZNH")) return CirculoReportStatus.NO_HIT;
        if (curp.startsWith("ZZNF")) return CirculoReportStatus.NOT_FOUND;
        return null;
    }
}
