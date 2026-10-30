package com.fintech.beneficiary.infrastructure.adapter.out.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La ficha de party tal como la devuelve {@code GET /api/v1/parties/by-prospect/{id}}.
 *
 * <p>El RFC y la CURP se leen de aquí para el SPEI de la colocación. El record tiene dos
 * constructores —con y sin identificadores fiscales— y Jackson tiene que seguir usando el
 * canónico: si eligiera el corto, el RFC llegaría nulo sin que nada fallara y el pago saldría sin él.
 */
class PartyResponseContractTest {

    /** Como el {@code WebClient} por defecto: campos desconocidos no rompen. */
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("la ficha trae nombre, RFC y CURP de la persona con los nombres de party")
    void leeLosIdentificadoresFiscales() throws Exception {
        String json = """
                {
                  "partyId": "7fb4f619-c56f-465c-bd8e-9ccbbfbe8815",
                  "prospectId": "90336b44-6577-49bf-8219-8e5cfe8f96f1",
                  "evaluationId": null,
                  "partyType": "INDIVIDUAL",
                  "status": "PROSPECT",
                  "firstName": "Rosa Elena",
                  "lastName1": "Ibarra",
                  "lastName2": "Salgado",
                  "curp": "IASR900612MDFBAO56",
                  "rfc": "IASR900612B0B",
                  "dateOfBirth": "1990-06-12",
                  "identityStatus": "VERIFIED",
                  "screeningStatus": "CLEARED",
                  "verificationClear": true,
                  "verificationBlockReason": null,
                  "createdAt": "2026-10-30T07:20:50.143088Z"
                }
                """;

        PartyClient.PartyResponse p = mapper.readValue(json, PartyClient.PartyResponse.class);

        assertThat(p.fullName()).isEqualTo("Rosa Elena Ibarra Salgado");
        assertThat(p.rfc()).isEqualTo("IASR900612B0B");
        assertThat(p.curp()).isEqualTo("IASR900612MDFBAO56");
        assertThat(p.taxId()).isEqualTo("IASR900612B0B");
        assertThat(p.identityStatus()).isEqualTo("VERIFIED");
    }
}
