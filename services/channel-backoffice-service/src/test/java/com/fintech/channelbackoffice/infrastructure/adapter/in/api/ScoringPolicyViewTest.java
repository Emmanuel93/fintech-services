package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.NoHistoryView;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.RequirementView;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.ScoringPolicyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La vista de la política lleva lo que hace con quien no tiene historial en buró. Antes el mapeo
 * campo por campo lo habría tirado sin que nada fallara: la consola lo guardaba y no lo veía.
 */
class ScoringPolicyViewTest {

    private static ScoringPolicyView politica(NoHistoryView sinHistorial) {
        return new ScoringPolicyView(UUID.randomUUID(), "INDIVIDUAL", "PERSONAL_LOAN", "Estudiantes", null,
                true, 2, List.of(), List.of(), sinHistorial);
    }

    @Test
    @DisplayName("el score sin historial y lo que se pide llegan a la consola, con su etiqueta")
    @SuppressWarnings("unchecked")
    void llegaLoQueSePide() {
        Map<String, Object> v = BackofficeViews.policy(politica(new NoHistoryView(300,
                List.of(new RequirementView("PROOF_OF_ENROLLMENT", "Constancia de estudios vigente")))));

        Map<String, Object> sinHistorial = (Map<String, Object>) v.get("sinHistorial");
        assertThat(sinHistorial).containsEntry("score", 300);
        assertThat((List<Map<String, Object>>) sinHistorial.get("requisitos")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("codigo", "PROOF_OF_ENROLLMENT")
                        .containsEntry("etiqueta", "Constancia de estudios vigente"));
    }

    @Test
    @DisplayName("una política anterior al campo se pinta como lo que hace: score 0 y nada que pedir")
    @SuppressWarnings("unchecked")
    void politicaAnterior() {
        Map<String, Object> sinHistorial = (Map<String, Object>) BackofficeViews.policy(politica(null)).get("sinHistorial");

        assertThat(sinHistorial).containsEntry("score", 0);
        assertThat((List<?>) sinHistorial.get("requisitos")).isEmpty();
    }
}
