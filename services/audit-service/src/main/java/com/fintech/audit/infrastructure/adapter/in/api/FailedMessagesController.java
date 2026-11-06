package com.fintech.audit.infrastructure.adapter.in.api;

import com.fintech.audit.application.service.failure.FailedMessageService;
import com.fintech.audit.domain.failure.FailedMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit/failures")
@Tag(name = "Mensajes fallidos", description = "Lo que un servicio no pudo procesar tras sus reintentos: buscar por correlation-id, reprocesar, descartar")
class FailedMessagesController {

    private final FailedMessageService failures;

    FailedMessagesController(FailedMessageService failures) {
        this.failures = failures;
    }

    @GetMapping
    @Operation(summary = "Buscar mensajes fallidos por correlation-id, tópico o estatus (PENDING, REPLAYED, DISCARDED)")
    List<FailedMessage> search(@RequestParam(required = false) String correlationId,
                               @RequestParam(required = false) String topic,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        return failures.search(correlationId, topic, status, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Un mensaje fallido, con su contenido y la causa")
    FailedMessage get(@PathVariable UUID id) {
        return failures.get(id);
    }

    @PostMapping("/{id}/replay")
    @Operation(summary = "Reenviarlo a su tópico original, tal como llegó; el consumidor es idempotente")
    FailedMessage replay(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body, Authentication auth) {
        return failures.replay(id, auth.getName(), body == null ? null : body.get("note"));
    }

    @PostMapping("/{id}/discard")
    @Operation(summary = "Descartarlo con el motivo: no se reprocesará")
    FailedMessage discard(@PathVariable UUID id, @RequestBody Map<String, String> body, Authentication auth) {
        return failures.discard(id, auth.getName(), body == null ? null : body.get("note"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
