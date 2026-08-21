package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class BackofficeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BackofficeExceptionHandler.class);

    /**
     * Los clientes de dominio ya vienen con el status correcto; aquí solo se envuelve en
     * ProblemDetail para que la consola reciba siempre la misma forma de error.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleDownstream(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        pd.setType(URI.create("https://fintech.com/errors/BO_DOWNSTREAM"));
        return pd;
    }

    /** Un servicio de dominio caído es 503, no 500: la consola puede reintentar. */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail handleUnreachable(ResourceAccessException ex) {
        log.error("Servicio de dominio inalcanzable", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Un servicio de dominio no está disponible");
        pd.setType(URI.create("https://fintech.com/errors/BO_DOWNSTREAM_UNAVAILABLE"));
        return pd;
    }
}
