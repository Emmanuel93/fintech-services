package com.fintech.scoring.infrastructure.adapter.out.circulo;

import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.*;
import com.fintech.scoring.infrastructure.adapter.out.circulo.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Adaptador real contra Círculo de Crédito. Activo salvo que {@code fintech.circulo.mock-enabled=true}. */
@Component
@ConditionalOnProperty(name = "fintech.circulo.mock-enabled", havingValue = "false", matchIfMissing = true)
class CirculoCreditoAdapter implements CirculoGateway {

    private static final Logger log = LoggerFactory.getLogger(CirculoCreditoAdapter.class);
    private static final String ENDPOINT = "/v2/rccficoscore";

    private final RestClient restClient;

    CirculoCreditoAdapter(RestClient circuloRestClient) {
        this.restClient = circuloRestClient;
    }

    @Override
    public CirculoReport query(UUID reportId, UUID prefetchId, UUID prospectId,
                               CirculoQueryRequest req) {
        CdcPersonaPeticion request = buildRequest(req);

        log.info("Querying Círculo de Crédito prospectId={} curp={}", prospectId, req.curp());

        try {
            CdcRespuesta response = restClient.post()
                    .uri(ENDPOINT)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (r, res) -> {
                        if (res.getStatusCode() == HttpStatus.NO_CONTENT) return;
                        log.warn("CDC 4xx prospectId={} status={}", prospectId, res.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (r, res) -> {
                        log.error("CDC 5xx prospectId={} status={}", prospectId, res.getStatusCode());
                        throw new RuntimeException("CDC server error: " + res.getStatusCode());
                    })
                    .body(CdcRespuesta.class);

            if (response == null) {
                return CirculoReport.builder(reportId, prefetchId, prospectId)
                        .status(CirculoReportStatus.NO_HIT)
                        .errorMessage("Círculo de Crédito returned empty response (204)")
                        .build();
            }

            return mapSuccess(reportId, prefetchId, prospectId, response);

        } catch (Exception ex) {
            log.error("Círculo de Crédito call failed prospectId={}", prospectId, ex);
            return CirculoReport.builder(reportId, prefetchId, prospectId)
                    .status(CirculoReportStatus.ERROR)
                    .errorCode("CDC_CALL_FAILED")
                    .errorMessage(ex.getMessage())
                    .build();
        }
    }

    // ── Request ──────────────────────────────────────────────────────────────

    private CdcPersonaPeticion buildRequest(CirculoQueryRequest req) {
        String direccion = buildDireccion(req);
        String municipio = (req.municipality() != null && !req.municipality().isBlank())
                ? req.municipality().toUpperCase()
                : req.city().toUpperCase();

        CdcDomicilioPeticion domicilio = new CdcDomicilioPeticion(
                sanitize(direccion),
                sanitize(req.neighborhood()),
                sanitize(municipio),
                sanitize(req.city()),
                req.state().toUpperCase(),
                req.postalCode());

        return new CdcPersonaPeticion(
                sanitize(req.apellidoPaterno()),
                sanitize(req.apellidoMaterno()),
                null,
                sanitize(req.primerNombre()),
                null,
                req.fechaNacimiento().toString(),
                req.rfc() != null ? req.rfc().toUpperCase() : null,
                req.curp().toUpperCase(),
                "MX",
                domicilio);
    }

    private String buildDireccion(CirculoQueryRequest req) {
        StringBuilder sb = new StringBuilder(req.street());
        if (req.exteriorNumber() != null && !req.exteriorNumber().isBlank()) {
            sb.append(" ").append(req.exteriorNumber());
        }
        if (req.interiorNumber() != null && !req.interiorNumber().isBlank()) {
            sb.append(" INT ").append(req.interiorNumber());
        }
        return sb.toString();
    }

    private String sanitize(String value) {
        if (value == null) return null;
        return value.toUpperCase()
                .replace('Á', 'A').replace('É', 'E').replace('Í', 'I')
                .replace('Ó', 'O').replace('Ú', 'U').replace('Ü', 'U')
                .replace('Ñ', 'N')
                .replaceAll("[`,\\-/;:!?]", "");
    }

    // ── Response mapper ──────────────────────────────────────────────────────

    private CirculoReport mapSuccess(UUID reportId, UUID prefetchId, UUID prospectId,
                                     CdcRespuesta r) {
        CirculoReport.Builder builder = CirculoReport.builder(reportId, prefetchId, prospectId)
                .status(CirculoReportStatus.SUCCESS)
                .folioConsulta(r.folioConsulta())
                .folioOtorgante(r.folioConsultaOtorgante())
                .claveOtorgante(r.claveOtorgante())
                .declaracionesConsumidor(r.declaracionesConsumidor());

        if (r.persona() != null) {
            CdcPersonaRespuesta p = r.persona();
            builder.personaNombres(p.nombres())
                    .personaApellidoPaterno(p.apellidoPaterno())
                    .personaApellidoMaterno(p.apellidoMaterno())
                    .personaFechaNacimiento(parseDate(p.fechaNacimiento()))
                    .personaRfc(p.rfc())
                    .personaCurp(p.curp())
                    .personaNss(p.numeroSeguridadSocial())
                    .personaSexo(p.sexo())
                    .personaEstadoCivil(p.estadoCivil())
                    .personaNacionalidad(p.nacionalidad())
                    .personaNumDependientes(p.numeroDependientes());
        }

        if (r.scores() != null && !r.scores().isEmpty()) {
            CdcScore fico = r.scores().stream()
                    .filter(s -> "FICO".equalsIgnoreCase(s.nombreScore()))
                    .findFirst()
                    .orElse(r.scores().get(0));
            builder.ficoScoreValor(fico.valor());
            if (fico.razones() != null) {
                builder.ficoScoreRazones(String.join(",", fico.razones()));
            }
        }

        CirculoReport report = builder.build();

        mapCredits(report, r.creditos());
        mapAddresses(report, r.domicilios());
        mapEmployments(report, r.empleos());
        mapInquiries(report, r.consultas());
        mapScores(report, r.scores());

        return report;
    }

    private void mapCredits(CirculoReport report, List<CdcCredito> creditos) {
        if (creditos == null) return;
        for (CdcCredito c : creditos) {
            report.addCredit(new CirculoCredit(
                    UUID.randomUUID(), report,
                    c.claveOtorgante(), c.nombreOtorgante(), c.cuentaActual(),
                    c.tipoResponsabilidad(), c.tipoCuenta(), c.tipoCredito(),
                    c.claveUnidadMonetaria(),
                    c.valorActivoValuacion() != null ? BigDecimal.valueOf(c.valorActivoValuacion()) : null,
                    c.numeroPagos(), c.frecuenciaPagos(), c.montoPagar(),
                    parseDate(c.fechaAperturaCuenta()), parseDate(c.fechaUltimoPago()),
                    parseDate(c.fechaUltimaCompra()), parseDate(c.fechaCierreCuenta()),
                    parseDate(c.fechaReporte()), parseDate(c.ultimaFechaSaldoCero()),
                    c.creditoMaximo(), c.saldoActual(), c.limiteCredito(),
                    c.saldoVencido(), c.numeroPagosVencidos(), c.pagoActual(),
                    c.historicoPagos(),
                    parseDate(c.fechaRecienteHistoricoPagos()),
                    parseDate(c.fechaAntiguaHistoricoPagos()),
                    c.clavePrevencion(), c.totalPagosReportados(),
                    c.peorAtraso(), parseDate(c.fechaPeorAtraso()),
                    c.saldoVencidoPeorAtraso(), c.montoUltimoPago(),
                    c.registroImpugnado(), parseDate(c.fechaActualizacion())));
        }
    }

    private void mapAddresses(CirculoReport report, List<CdcDomicilioRespuesta> domicilios) {
        if (domicilios == null) return;
        for (CdcDomicilioRespuesta d : domicilios) {
            report.addAddress(new CirculoAddress(
                    UUID.randomUUID(), report,
                    d.direccion(), d.coloniaPoblacion(), d.delegacionMunicipio(),
                    d.ciudad(), d.estado(), d.cp(), d.tipoDomicilio(),
                    parseDate(d.fechaResidencia()), parseDate(d.fechaRegistroDomicilio()),
                    d.idDomicilio()));
        }
    }

    private void mapEmployments(CirculoReport report, List<CdcEmpleo> empleos) {
        if (empleos == null) return;
        for (CdcEmpleo e : empleos) {
            report.addEmployment(new CirculoEmployment(
                    UUID.randomUUID(), report,
                    e.nombreEmpresa(), e.puesto(), e.salarioMensual(), e.claveMoneda(),
                    parseDate(e.fechaContratacion()), parseDate(e.fechaUltimoDiaEmpleo()),
                    parseDate(e.fechaVerificacionEmpleo()), e.ciudad(), e.estado()));
        }
    }

    private void mapInquiries(CirculoReport report, List<CdcConsulta> consultas) {
        if (consultas == null) return;
        for (CdcConsulta c : consultas) {
            report.addInquiry(new CirculoInquiry(
                    UUID.randomUUID(), report,
                    parseDate(c.fechaConsulta()), c.nombreOtorgante(),
                    c.tipoCredito(), c.claveUnidadMonetaria(),
                    c.importeCredito(), c.tipoResponsabilidad()));
        }
    }

    private void mapScores(CirculoReport report, List<CdcScore> scores) {
        if (scores == null) return;
        for (CdcScore s : scores) {
            String razones = s.razones() != null ? String.join(",", s.razones()) : null;
            report.addScore(new CirculoScore(UUID.randomUUID(), report,
                    s.nombreScore(), s.valor(), razones));
        }
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
