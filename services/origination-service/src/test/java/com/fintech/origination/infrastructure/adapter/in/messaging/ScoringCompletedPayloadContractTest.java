package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El evento que publica scoring, tal como sale, se lee aquí: con lo que pide la política y sin ello.
 */
class ScoringCompletedPayloadContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("con datos adicionales: llegan las etiquetas que lee la mesa")
    void conRequisitos() throws Exception {
        String json = """
                {"eventId":"e1","applicationId":"7f1c6a52-1111-4a0e-9a57-7c7a6f0d1e01",
                 "prospectId":"7f1c6a52-2222-4a0e-9a57-7c7a6f0d1e01","productTypeIntent":"PERSONAL_LOAN",
                 "totalScore":300,"riskLevel":"MEDIO","decision":"MANUAL_REVIEW","evaluatedAt":"2026-10-31T01:50:00Z",
                 "requirements":[{"code":"PROOF_OF_ENROLLMENT","label":"Constancia de estudios vigente"},
                                 {"code":"GUARANTOR","label":"Aval u obligado solidario"}]}""";

        ScoringCompletedPayload p = mapper.readValue(json, ScoringCompletedPayload.class);

        assertThat(p.requirementLabels()).containsExactly("Constancia de estudios vigente", "Aval u obligado solidario");
    }

    @Test
    @DisplayName("un evento de antes, sin el campo: nada que pedir, no revienta")
    void eventoAnteriorSinRequisitos() throws Exception {
        String json = """
                {"eventId":"e1","prospectId":"7f1c6a52-2222-4a0e-9a57-7c7a6f0d1e01",
                 "productTypeIntent":"PERSONAL_LOAN","totalScore":430,"riskLevel":"BAJO","decision":"AUTO_APPROVED"}""";

        assertThat(mapper.readValue(json, ScoringCompletedPayload.class).requirementLabels()).isEmpty();
    }
}
