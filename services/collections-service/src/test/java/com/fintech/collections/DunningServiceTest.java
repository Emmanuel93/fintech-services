package com.fintech.collections;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.CommunicationHoldRepository;
import com.fintech.collections.application.service.DunningService;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.DunningStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DunningServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock CommunicationHoldRepository holdRepository;
    @Mock CollectionsEventPublisher eventPublisher;

    CollectionsProperties properties;
    DunningService service;

    @BeforeEach
    void setUp() {
        properties = new CollectionsProperties();
        service = new DunningService(caseRepository, holdRepository, eventPublisher, properties);
    }

    /**
     * La corrida sólo puede evaluarse dentro de la ventana permitida; fuera de ella el servicio no
     * manda nada por diseño. En vez de falsear el reloj, la prueba se salta cuando corre de noche:
     * un test que fija la hora del sistema para pasar dejaría de comprobar justamente la guarda.
     */
    private void assumeDentroDeVentana() {
        int hora = LocalTime.now().getHour();
        assumeThat(hora)
                .as("la corrida sólo publica dentro de la ventana CONDUSEF")
                .isBetween(properties.getContactAllowedHoursStart(), properties.getContactAllowedHoursEnd() - 1);
    }

    private CollectionCase caseWithDays(int days) {
        return CollectionCase.open(UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                days, new BigDecimal("2000"), "AUTO_NOTIFY");
    }

    @Test
    void publica_unaPeticionPorCasoDentroDelCicloDeCincoDias() {
        assumeDentroDeVentana();
        given(caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(any(), any(Integer.class)))
                .willReturn(List.of(caseWithDays(1), caseWithDays(3), caseWithDays(5)));
        given(holdRepository.findCaseIdsWithActiveHold(any())).willReturn(List.of());

        assertThat(service.runDailyCycle()).isEqualTo(3);
        verify(eventPublisher, org.mockito.Mockito.times(3))
                .publishDunningRequested(any(), any(DunningStep.class));
    }

    @Test
    void noPublica_paraCasosFueraDelCicloDeCincoDias() {
        assumeDentroDeVentana();
        // 6 y 40 días ya pasaron el ciclo automático: de ahí en adelante gestiona una persona.
        given(caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(any(), any(Integer.class)))
                .willReturn(List.of(caseWithDays(6), caseWithDays(40)));
        given(holdRepository.findCaseIdsWithActiveHold(any())).willReturn(List.of());

        assertThat(service.runDailyCycle()).isZero();
        verify(eventPublisher, never()).publishDunningRequested(any(), any());
    }

    @Test
    void noPublica_paraCasosSilenciadosPorUnFreno() {
        assumeDentroDeVentana();
        CollectionCase silenciado = caseWithDays(2);
        CollectionCase libre      = caseWithDays(2);
        given(caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(any(), any(Integer.class)))
                .willReturn(List.of(silenciado, libre));
        given(holdRepository.findCaseIdsWithActiveHold(any()))
                .willReturn(List.of(silenciado.getCaseId()));

        assertThat(service.runDailyCycle()).isEqualTo(1);
        verify(eventPublisher).publishDunningRequested(eqCase(libre), any());
    }

    @Test
    void noPublicaNada_siLaCadenciaEstaApagada() {
        properties.setDunningEnabled(false);

        assertThat(service.runDailyCycle()).isZero();
        verify(eventPublisher, never()).publishDunningRequested(any(), any());
    }

    @Test
    void elCiclo_asignaUnEscalonDistintoPorDia() {
        assertThat(DunningStep.forDay(1)).isEqualTo(DunningStep.RECORDATORIO);
        assertThat(DunningStep.forDay(4)).isEqualTo(DunningStep.HISTORIAL);
        assertThat(DunningStep.forDay(5)).isEqualTo(DunningStep.OFRECER_AYUDA);
        assertThat(DunningStep.forDay(6)).isNull();
        assertThat(DunningStep.cicloDias()).isEqualTo(5);
    }

    private static CollectionCase eqCase(CollectionCase expected) {
        return org.mockito.ArgumentMatchers.argThat(
                c -> c != null && c.getCaseId().equals(expected.getCaseId()));
    }
}
