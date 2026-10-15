package com.fintech.compliance.infrastructure.adapter.in.api;

import com.fintech.compliance.domain.VerificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Traduce los rechazos del dominio a respuestas con motivo.
 *
 * <p>Sin esto, «falta el argumento de la adjudicación» sale como un 500 — y un 500 le dice a quien
 * llama que el servidor se rompió, cuando lo que pasó es que su petición estaba incompleta. La
 * diferencia importa: con 500 se reintenta; con 400 se corrige.
 */
@RestControllerAdvice
class ComplianceExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> onInvalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> onConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VerificationNotFoundException.class)
    ResponseEntity<Map<String, Object>> onNotFound(VerificationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
