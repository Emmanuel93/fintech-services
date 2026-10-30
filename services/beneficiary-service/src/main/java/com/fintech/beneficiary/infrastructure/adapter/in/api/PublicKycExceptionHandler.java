package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.domain.PublicKycException;
import com.fintech.beneficiary.domain.PlacementValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Los errores de la web pública, en el formato que la página entiende: {@code {error, message, …}}.
 *
 * <p>Acotado a {@link PublicKycController} para no cambiarle el formato a la API privada, que habla
 * {@code ProblemDetail}. Va antes que {@code BeneficiaryExceptionHandler} para ganarle a sus
 * manejadores genéricos sobre esta superficie.
 *
 * <p>Cualquier cosa que no sea un error conocido de la liga se responde genérico: una página
 * pública no debe ver el mensaje de un servicio interno ni un stack trace.
 */
@RestControllerAdvice(assignableTypes = PublicKycController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicKycExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublicKycExceptionHandler.class);

    @ExceptionHandler(PublicKycException.class)
    ResponseEntity<Map<String, Object>> handle(PublicKycException ex) {
        HttpStatus status = statusOf(ex);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (ex instanceof PublicKycException.TooManyAttempts t && t.retryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(t.retryAfterSeconds()));
        }
        return builder.body(body(ex.wireCode(), ex.getMessage(), ex.details()));
    }

    /** Un cuerpo que no es JSON, o con un número donde va texto, es un dato mal capturado. */
    @ExceptionHandler({HttpMessageNotReadableException.class, PlacementValidationException.class})
    ResponseEntity<Map<String, Object>> unreadable(Exception ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("DATOS_INVALIDOS", "Hay datos por corregir.",
                        Map.of("fields", Map.of("body", "El formato de los datos no es válido"))));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<Map<String, Object>> missingPart(Exception ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("DATOS_INVALIDOS", "Falta un archivo o no se pudo leer.",
                        Map.of("fields", Map.of("file", "Vuelve a tomar la foto"))));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unexpected(RuntimeException ex) {
        log.error("Error inesperado en la web pública de KYC", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("SERVICIO_NO_DISPONIBLE",
                        "Algo falló de nuestro lado. Intenta de nuevo en unos minutos.", Map.of()));
    }

    static HttpStatus statusOf(PublicKycException ex) {
        return switch (ex) {
            case PublicKycException.InviteNotValid e        -> HttpStatus.NOT_FOUND;
            case PublicKycException.TooManyAttempts e       -> HttpStatus.TOO_MANY_REQUESTS;
            case PublicKycException.WrongCode e             -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PublicKycException.SessionExpired e        -> HttpStatus.UNAUTHORIZED;
            case PublicKycException.InvalidData e           -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PublicKycException.IncompleteFile e        -> HttpStatus.CONFLICT;
            case PublicKycException.DependencyUnavailable e -> HttpStatus.SERVICE_UNAVAILABLE;
            default                                         -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    static Map<String, Object> body(String error, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.putAll(details);
        return body;
    }
}
