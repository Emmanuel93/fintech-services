package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Programas de apoyo por contingencia, desde la consola (BK-32, BK-33).
 *
 * <p>Lo otorga la <b>institución</b>, no lo pide el cliente: por eso vive aquí y no en el BFF móvil,
 * al revés que diferir una compra o saltar un pago.
 *
 * <p>El ciclo es maker-checker y lo resuelve credit-portfolio a partir de la identidad que este BFF
 * reenvía. Aquí <b>no</b> se replica la regla de que quien propone no autoriza: dos sitios
 * decidiendo eso es un sitio de más donde la separación de funciones puede aflojarse sin que nadie
 * lo note. Si el mismo empleado intenta las dos mitades, el dominio responde y el motivo llega
 * intacto a la pantalla.
 *
 * <p>El padrón se simula antes de autorizar. Quien firma ve a cuántas cuentas alcanza el programa
 * <b>antes</b> de que se mueva un solo vencimiento — un apoyo masivo mal delimitado no se deshace
 * corriendo las fechas de vuelta.
 */
@RestController
@Tag(name = "Programas de apoyo", description = "Diferimiento masivo por contingencia (maker-checker)")
class ReliefProgramsController {

    private static final Logger log = LoggerFactory.getLogger(ReliefProgramsController.class);

    private final CreditPortfolioClient creditPortfolioClient;

    ReliefProgramsController(CreditPortfolioClient creditPortfolioClient) {
        this.creditPortfolioClient = creditPortfolioClient;
    }

    @PostMapping("/relief-programs")
    @Operation(summary = "Proponer un programa de apoyo (maker)")
    ResponseEntity<Map<String, Object>> proponer(@RequestBody Map<String, Object> cuerpo) {
        log.info("POST /relief-programs motivo={}", cuerpo.get("reason"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(creditPortfolioClient.proponerPrograma(cuerpo));
    }

    @GetMapping("/relief-programs/{programId}/padron")
    @Operation(summary = "A cuántas cuentas alcanzaría, sin tocar ninguna")
    ResponseEntity<Map<String, Object>> padron(@PathVariable UUID programId) {
        log.info("GET /relief-programs/{}/padron", programId);
        return ResponseEntity.ok(creditPortfolioClient.padronDelPrograma(programId));
    }

    @PostMapping("/relief-programs/{programId}/approve")
    @Operation(summary = "Autorizar el programa (checker) — no puede ser quien lo propuso")
    ResponseEntity<Map<String, Object>> autorizar(@PathVariable UUID programId) {
        log.info("POST /relief-programs/{}/approve", programId);
        return ResponseEntity.ok(creditPortfolioClient.autorizarPrograma(programId));
    }

    @PostMapping("/relief-programs/{programId}/grant")
    @Operation(summary = "Otorgar: corre los vencimientos de todo el padrón")
    ResponseEntity<Map<String, Object>> otorgar(@PathVariable UUID programId) {
        log.info("POST /relief-programs/{}/grant", programId);
        return ResponseEntity.accepted().body(creditPortfolioClient.otorgarPrograma(programId));
    }
}
