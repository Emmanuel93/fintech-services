package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El BFF entrega el ProblemDetail del dominio tal cual, para que la app sepa qué pasó.
 */
class MobileExceptionHandlerTest {

    private final MobileExceptionHandler handler = new MobileExceptionHandler(new ObjectMapper());

    @Test
    @DisplayName("🔑 la compuerta de identidad llega a la app con su type y sus estados, no como texto")
    void laCompuertaLlegaComoTal() {
        String deOrigination = """
                {"type":"https://fintech.com/errors/ORIGINATION_PERSON_NOT_VERIFIED","title":"La persona aún no está verificada",
                 "status":422,"detail":"La persona del prospecto 7f1c no puede originar crédito: identidad IN_REVIEW",
                 "instance":"/api/v1/origination/applications","prospectId":"7f1c","reason":"identidad IN_REVIEW",
                 "identityStatus":"IN_REVIEW","screeningStatus":"CLEARED"}""";

        ProblemDetail pd = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, deOrigination));

        assertThat(pd.getStatus()).isEqualTo(422);
        assertThat(pd.getType().toString()).endsWith("ORIGINATION_PERSON_NOT_VERIFIED");
        assertThat(pd.getTitle()).isEqualTo("La persona aún no está verificada");
        assertThat(pd.getDetail()).contains("identidad IN_REVIEW");
        assertThat(pd.getProperties()).containsEntry("identityStatus", "IN_REVIEW")
                .containsEntry("screeningStatus", "CLEARED");
        // La ruta interna de origination no se le muestra a la app.
        assertThat(pd.getInstance()).isNull();
    }

    @Test
    @DisplayName("un error que no es ProblemDetail sigue saliendo como antes")
    void textoPlanoSigueIgual() {
        ProblemDetail pd = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream caído"));

        assertThat(pd.getType().toString()).endsWith("request-error");
        assertThat(pd.getDetail()).isEqualTo("upstream caído");
    }

    @Test
    @DisplayName("un JSON que no es ProblemDetail no se confunde con uno")
    void jsonCualquieraNoEsProblem() {
        ProblemDetail pd = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "{\"error\":\"duplicado\"}"));

        assertThat(pd.getType().toString()).endsWith("request-error");
    }
}
