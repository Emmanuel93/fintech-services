package com.fintech.scoring.infrastructure.adapter.out.circulo;

import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CirculoCreditoAdapterTest {

    @Mock RestClient restClient;
    @Mock RestClient.RequestBodyUriSpec uriSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

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
    void setUp() {
        adapter = new CirculoCreditoAdapter(restClient);
        lenient().doReturn(uriSpec).when(restClient).post();
        lenient().doReturn(bodySpec).when(uriSpec).uri(anyString());
        // Use any(Object.class) to force overload resolution to body(Object) — Spring 6.2 added
        // body(StreamingHttpOutputMessage.Body) which would otherwise win with a null argument.
        lenient().doReturn(bodySpec).when(bodySpec).body(any(Object.class));
        lenient().doReturn(responseSpec).when(bodySpec).retrieve();
        lenient().doReturn(responseSpec).when(responseSpec).onStatus(any(), any());
    }

    @Test
    void query_nullResponse_returnsNoHit() {
        given(responseSpec.body(any(Class.class))).willReturn(null);

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.NO_HIT);
        assertThat(result.getReportId()).isEqualTo(reportId);
        assertThat(result.getPrefetchId()).isEqualTo(prefetchId);
        assertThat(result.getProspectId()).isEqualTo(prospectId);
    }

    @Test
    void query_exception_returnsError() {
        given(responseSpec.body(any(Class.class))).willThrow(new RuntimeException("timeout"));

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, validRequest);

        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.ERROR);
        assertThat(result.getErrorCode()).isEqualTo("CDC_CALL_FAILED");
        assertThat(result.getErrorMessage()).contains("timeout");
    }

    @Test
    void query_specialCharsInName_sanitized() {
        // Verify sanitize is applied — we can only test via the outcome (no error)
        CirculoQueryRequest withAccents = new CirculoQueryRequest(
                "HERNÁNDEZ", "MUÑOZ", "JOSÉ",
                "HEMJ900101HDFRRQ03", null,
                LocalDate.of(1990, 1, 1),
                "Calle Número 1", "10", null,
                "Colonia", null, "CDMX", "CDMX", "03940");

        given(responseSpec.body(any(Class.class))).willReturn(null);

        CirculoReport result = adapter.query(reportId, prefetchId, prospectId, withAccents);

        // Sanitization happens transparently — no exception thrown
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(CirculoReportStatus.NO_HIT);
    }

    @Test
    void query_municipalityFallsBackToCity_whenNull() {
        CirculoQueryRequest noMunicipality = new CirculoQueryRequest(
                "LOPEZ", null, "ANA",
                "LOAA900101MDFPNA00", null,
                LocalDate.of(1990, 1, 1),
                "Calle 1", "10", null,
                "Colonia", null, "Guadalajara", "JAL", "44100");

        given(responseSpec.body(any(Class.class))).willReturn(null);

        // Should not throw even with null municipality — city used as fallback
        assertThatCode(() -> adapter.query(reportId, prefetchId, prospectId, noMunicipality))
                .doesNotThrowAnyException();
    }
}
