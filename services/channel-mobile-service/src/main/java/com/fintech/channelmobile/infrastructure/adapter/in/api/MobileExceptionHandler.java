package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestControllerAdvice
class MobileExceptionHandler {

    /** Campos propios del ProblemDetail; el resto del cuerpo de arriba viaja como propiedades. */
    private static final Set<String> CAMPOS_ESTANDAR = Set.of("type", "title", "status", "detail", "instance");

    private final ObjectMapper mapper;

    MobileExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create("https://fintech.com/errors/validation-error"));
        return pd;
    }

    /**
     * Un error de un servicio de dominio llega aquí con el cuerpo de arriba como texto.
     *
     * <p>Si ese cuerpo es un ProblemDetail, se entrega <b>tal cual</b> —su {@code type}, su título y
     * sus propiedades—. Antes se envolvía como cadena dentro de {@code detail} de un
     * {@code request-error} genérico: la app recibía el 422 de la compuerta de identidad y no podía
     * distinguirlo de cualquier otro 422, así que le mostraba a la persona «no pudimos enviar tu
     * solicitud» cuando lo que pasaba era «estamos verificando tu identidad».
     */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        Map<String, Object> upstream = problemOf(ex.getReason());
        if (upstream != null) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, (String) upstream.get("detail"));
            pd.setType(URI.create(String.valueOf(upstream.get("type"))));
            if (upstream.get("title") instanceof String title) pd.setTitle(title);
            upstream.forEach((k, v) -> { if (!CAMPOS_ESTANDAR.contains(k)) pd.setProperty(k, v); });
            return pd;
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getReason());
        pd.setType(URI.create("https://fintech.com/errors/request-error"));
        return pd;
    }

    /** El cuerpo de arriba como ProblemDetail, o {@code null} si no lo es. */
    private Map<String, Object> problemOf(String body) {
        if (body == null || !body.trim().startsWith("{")) return null;
        try {
            Map<String, Object> m = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            return m.get("type") instanceof String && m.containsKey("status") ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Errores devueltos por identity-service u origination-service via WebClient
    @ExceptionHandler(WebClientResponseException.class)
    ProblemDetail handleWebClientError(WebClientResponseException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status,
                "Error del servicio backend: " + ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/upstream-error"));
        return pd;
    }
}
