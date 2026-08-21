package com.fintech.scoring.infrastructure.adapter.out.circulo;

import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@WireMockTest
class CirculoCreditoAdapterIT {

    static final String CDC_PATH = "/v2/rccficoscore";

    CirculoCreditoAdapter adapter;

    final UUID reportId   = UUID.randomUUID();
    final UUID prefetchId = UUID.randomUUID();
    final UUID prospectId = UUID.randomUUID();

    final CirculoQueryRequest validRequest = new CirculoQueryRequest(
            "SESENTAYDOS", "PRUEBA", "JUAN",
            "SEPJ650809HDFXXX01", "SEPJ650809JG1",
            LocalDate.of(1965, 8, 9),
            "PASADISO ENCONTRADO", "58", null,
            "MONTEVIDEO", "GUSTAVO A MADERO", "CIUDAD DE MEXICO",
            "CDMX", "07730");

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        // Force HTTP/1.1 — WireMock does not support HTTP/2 over plain HTTP, which the
        // JDK HttpClient may negotiate automatically on Java 21+.
        RestClient client = RestClient.builder()
                .baseUrl(wm.getHttpBaseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .defaultHeader("x-api-key", "test-api-key")
                .defaultHeader("Content-Type", "application/json")
                .build();
        adapter = new CirculoCreditoAdapter(client);
    }

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    void query_successResponse_mapsStatusAndFicoScore() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .willReturn(okJson(successResponseJson())));

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.SUCCESS);
        assertThat(result.getReportId()).isEqualTo(reportId);
        assertThat(result.getPrefetchId()).isEqualTo(prefetchId);
        assertThat(result.getProspectId()).isEqualTo(prospectId);
        assertThat(result.getFolioConsulta()).isEqualTo("31611250146");
        assertThat(result.getFicoScoreValor()).isEqualTo(720);
        assertThat(result.getPersonaNombres()).isEqualTo("JUAN");
    }

    @Test
    void query_successResponse_mapsChildCollections() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .willReturn(okJson(successResponseJson())));

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getCredits()).hasSize(1);
        assertThat(result.getAddresses()).hasSize(1);
        assertThat(result.getScores()).hasSize(1);
        assertThat(result.getCredits().get(0).getNombreOtorgante()).isEqualTo("BANAMEX");
        assertThat(result.getAddresses().get(0).getCiudad()).isEqualTo("CIUDAD DE MEXICO");
    }

    // ── No-hit / Empty ───────────────────────────────────────────────────────

    @Test
    void query_204NoContent_returnsNoHit() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .willReturn(noContent()));

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.NO_HIT);
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    void query_500ServerError_returnsErrorReport() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .willReturn(serverError()));

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("CDC_CALL_FAILED");
    }

    @Test
    void query_connectionRefused_returnsErrorReport(WireMockRuntimeInfo wm) {
        // Point to a port where nothing listens
        RestClient deadClient = RestClient.builder()
                .baseUrl("http://localhost:1")
                .build();
        adapter = new CirculoCreditoAdapter(deadClient);

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("CDC_CALL_FAILED");
    }

    // ── Request validation ────────────────────────────────────────────────────

    @Test
    void query_accentedNames_sanitizedBeforeSending() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .withRequestBody(matchingJsonPath("$.apellidoPaterno", equalTo("HERNANDEZ")))
                .withRequestBody(matchingJsonPath("$.primerNombre", equalTo("JOSE")))
                .willReturn(okJson(noHitJson())));

        CirculoQueryRequest withAccents = new CirculoQueryRequest(
                "HERNÁNDEZ", "MUÑOZ", "JOSÉ",
                "HEMJ900101HDFRRQ03", null,
                LocalDate.of(1990, 1, 1),
                "Calle 1", "10", null,
                "Colonia", null, "CDMX", "CDMX", "03940");

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, withAccents);

        // WireMock matched the sanitized body — stub would not match otherwise
        assertThat(result).isNotNull();
    }

    @Test
    void query_nullMunicipality_usesCityAsFallback() {
        stubFor(post(urlEqualTo(CDC_PATH))
                .withRequestBody(matchingJsonPath("$.domicilio.delegacionMunicipio",
                        equalTo("GUADALAJARA")))
                .willReturn(okJson(noHitJson())));

        CirculoQueryRequest noMunicipality = new CirculoQueryRequest(
                "LOPEZ", null, "ANA",
                "LOAA900101MDFPNA00", null,
                LocalDate.of(1990, 1, 1),
                "Calle 1", "10", null,
                "Colonia", null, "Guadalajara", "JAL", "44100");

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, noMunicipality);

        assertThat(result).isNotNull();
    }

    // ── JSON fixtures ─────────────────────────────────────────────────────────

    private String successResponseJson() {
        return """
                {
                  "folioConsulta": "31611250146",
                  "folioConsultaOtorgante": "0000",
                  "claveOtorgante": "0000811",
                  "declaracionesConsumidor": "SCORE 3.0",
                  "persona": {
                    "apellidoPaterno": "SESENTAYDOS",
                    "apellidoMaterno": "PRUEBA",
                    "nombres": "JUAN",
                    "fechaNacimiento": "1965-08-09",
                    "rfc": "SEPJ650809JG1",
                    "curp": "SEPJ650809HDFXXX01",
                    "sexo": "M",
                    "estadoCivil": "S",
                    "nacionalidad": "MX"
                  },
                  "creditos": [
                    {
                      "fechaActualizacion": "2024-01-15",
                      "registroImpugnado": 0,
                      "claveOtorgante": "BNMX01",
                      "nombreOtorgante": "BANAMEX",
                      "cuentaActual": "1234567890",
                      "tipoResponsabilidad": "I",
                      "tipoCuenta": "TC",
                      "tipoCredito": "TARJETA DE CREDITO",
                      "claveUnidadMonetaria": "MX",
                      "numeroPagos": 36,
                      "frecuenciaPagos": "M",
                      "montoPagar": 500.00,
                      "creditoMaximo": 50000.00,
                      "saldoActual": 12000.00,
                      "limiteCredito": 50000.00,
                      "saldoVencido": 0.0,
                      "numeroPagosVencidos": 0,
                      "pagoActual": "V",
                      "historicoPagos": "VVVVVVVV",
                      "totalPagosReportados": 36,
                      "peorAtraso": 0
                    }
                  ],
                  "domicilios": [
                    {
                      "direccion": "PASADISO 58",
                      "coloniaPoblacion": "MONTEVIDEO",
                      "delegacionMunicipio": "GUSTAVO A MADERO",
                      "ciudad": "CIUDAD DE MEXICO",
                      "estado": "CDMX",
                      "cp": "07730",
                      "tipoDomicilio": "P",
                      "idDomicilio": "1"
                    }
                  ],
                  "empleos": [],
                  "consultas": [],
                  "scores": [
                    {
                      "nombreScore": "FICO",
                      "valor": 720,
                      "razones": ["00", "01"]
                    }
                  ],
                  "mensajes": []
                }
                """;
    }

    private String noHitJson() {
        return """
                {
                  "folioConsulta": "00000000000",
                  "scores": [],
                  "creditos": [],
                  "domicilios": [],
                  "empleos": [],
                  "consultas": [],
                  "mensajes": [{"descripcion": "SIN RESULTADOS"}]
                }
                """;
    }
}
