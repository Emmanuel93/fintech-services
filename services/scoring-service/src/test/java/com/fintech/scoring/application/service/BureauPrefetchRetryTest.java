package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.application.port.out.BureauUnavailableException;
import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.domain.BureauPrefetch;
import com.fintech.scoring.domain.BureauPrefetchStatus;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

/**
 * El reintento del buró: lo transitorio se reintenta con backoff hasta tres veces, lo definitivo no,
 * y en cualquier caso queda guardado el estado, el motivo y cuántas veces se llamó.
 */
@ExtendWith(MockitoExtension.class)
class BureauPrefetchRetryTest {

    @Mock BureauPrefetchRepository prefetchRepository;
    @Mock CirculoReportRepository  reportRepository;
    @Mock CirculoGateway           circuloGateway;

    final UUID prospectId = UUID.randomUUID();
    final List<Duration> esperas = new ArrayList<>();
    BureauPrefetchService service;

    @BeforeEach
    void setUp() {
        service = new BureauPrefetchService(prefetchRepository, reportRepository, circuloGateway,
                BureauRetryPolicy.defaults(), esperas::add);
        lenient().when(prefetchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(prefetchRepository.existsActiveByProspectId(prospectId)).thenReturn(false);
    }

    private void prefetch() {
        service.initiate(prospectId, "SEPJ650809HDFXXX01", "evt-1", "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9), "Calle 1", "10", null, "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", "PERSONAL_LOAN");
    }

    private BureauPrefetch ultimoPrefetch() {
        ArgumentCaptor<BureauPrefetch> captor = ArgumentCaptor.forClass(BureauPrefetch.class);
        then(prefetchRepository).should(times(2)).save(captor.capture());
        return captor.getAllValues().get(1);
    }

    private CirculoReport exito() {
        return CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), prospectId)
                .status(CirculoReportStatus.SUCCESS).ficoScoreValor(720).build();
    }

    @Test
    void el_timeout_se_reintenta_con_backoff_y_se_recupera() {
        given(circuloGateway.query(any(), any(), eq(prospectId), any()))
                .willThrow(new BureauUnavailableException("timeout"))
                .willThrow(new BureauUnavailableException("timeout"))
                .willReturn(exito());

        prefetch();

        BureauPrefetch p = ultimoPrefetch();
        assertThat(p.getStatus()).isEqualTo(BureauPrefetchStatus.COMPLETED);
        assertThat(p.getAttempts()).isEqualTo(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void agotados_tres_reintentos_queda_failed_con_el_motivo_y_el_reporte_error_guardado() {
        given(circuloGateway.query(any(), any(), eq(prospectId), any()))
                .willThrow(new BureauUnavailableException("Read timed out"));

        prefetch();

        then(circuloGateway).should(times(4)).query(any(), any(), eq(prospectId), any());
        BureauPrefetch p = ultimoPrefetch();
        assertThat(p.getStatus()).isEqualTo(BureauPrefetchStatus.FAILED);
        assertThat(p.getAttempts()).isEqualTo(4);
        assertThat(p.getFailureReason()).contains("BUREAU_UNAVAILABLE").contains("4 intentos").contains("Read timed out");
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

        ArgumentCaptor<CirculoReport> report = ArgumentCaptor.forClass(CirculoReport.class);
        then(reportRepository).should().save(report.capture());
        assertThat(report.getValue().getStatus()).isEqualTo(CirculoReportStatus.ERROR);
        assertThat(report.getValue().getErrorCode()).isEqualTo("CDC_UNAVAILABLE");
    }

    @Test
    void un_no_del_buro_no_se_reintenta_pero_se_guarda() {
        given(circuloGateway.query(any(), any(), eq(prospectId), any()))
                .willReturn(CirculoReport.builder(UUID.randomUUID(), UUID.randomUUID(), prospectId)
                        .status(CirculoReportStatus.NO_HIT).errorMessage("sin historial").build());

        prefetch();

        then(circuloGateway).should(times(1)).query(any(), any(), eq(prospectId), any());
        assertThat(esperas).isEmpty();
        BureauPrefetch p = ultimoPrefetch();
        assertThat(p.getStatus()).isEqualTo(BureauPrefetchStatus.FAILED);
        assertThat(p.getAttempts()).isEqualTo(1);
        assertThat(p.getFailureReason()).startsWith("NO_HIT");
        then(reportRepository).should().save(any(CirculoReport.class));
    }

    @Test
    void el_backoff_crece_y_respeta_el_tope() {
        BureauRetryPolicy policy = new BureauRetryPolicy(6, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));
        assertThat(List.of(policy.backoffBefore(1), policy.backoffBefore(2), policy.backoffBefore(3),
                policy.backoffBefore(4), policy.backoffBefore(5)))
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                        Duration.ofSeconds(8), Duration.ofSeconds(10));
    }

    @Test
    void con_el_buro_caido_el_alta_espera_un_tiempo_acotado() {
        // Rendimiento: con la política por defecto, la espera entre intentos suma 7 s como máximo
        // (más el timeout de cada intento). Con backoff real de 10 ms la corrida completa es inmediata.
        BureauPrefetchService rapido = new BureauPrefetchService(prefetchRepository, reportRepository,
                circuloGateway, new BureauRetryPolicy(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(40)));
        given(circuloGateway.query(any(), any(), eq(prospectId), any()))
                .willThrow(new BureauUnavailableException("down"));

        long inicio = System.nanoTime();
        rapido.initiate(prospectId, "SEPJ650809HDFXXX01", "evt-1", "JUAN", "SESENTAYDOS", "PRUEBA", null,
                LocalDate.of(1965, 8, 9), "Calle 1", "10", null, "Col", null, "CDMX", "CDMX", "07730",
                "INDIVIDUAL", "PERSONAL_LOAN");
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(ms).isBetween(70L, 1_000L); // 10 + 20 + 40 ms de backoff real, sin dormir de más
        BureauRetryPolicy d = BureauRetryPolicy.defaults();
        long total = 0;
        for (int i = 1; i <= d.maxRetries(); i++) total += d.backoffBefore(i).toMillis();
        assertThat(total).isEqualTo(7_000L);
    }
}
