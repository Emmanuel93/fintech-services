package com.fintech.beneficiary.infrastructure.adapter.in.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Un archivo de más de 8 MB, en el formato de la web pública.
 *
 * <p>Va aparte de {@link PublicKycExceptionHandler} y <b>sin acotar</b> porque esta excepción
 * ocurre al parsear el multipart, antes de que Spring sepa qué controlador atiende: un advice
 * acotado a un controlador nunca la vería. Sólo la superficie pública recibe archivos, así que no
 * le cambia el formato a nadie más.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadLimitExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(PublicKycExceptionHandler.body("ARCHIVO_DEMASIADO_GRANDE",
                        "Cada archivo puede pesar hasta 8 MB.", Map.of()));
    }
}
