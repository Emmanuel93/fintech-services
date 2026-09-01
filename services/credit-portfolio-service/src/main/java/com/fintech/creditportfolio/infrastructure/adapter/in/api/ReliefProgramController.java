package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.in.ManageReliefProgramUseCase;
import com.fintech.creditportfolio.application.port.in.ManageReliefProgramUseCase.Padron;
import com.fintech.creditportfolio.application.port.in.ManageReliefProgramUseCase.Proponer;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Programas de apoyo por contingencia — backoffice.
 *
 * <p>El ciclo es <b>maker-checker</b>: quien propone no autoriza. La identidad viene del gateway en
 * {@code X-User-Id}, no del cuerpo: dejar que el cliente diga quién es convertiría la separación de
 * funciones en una declaración voluntaria.
 *
 * <p>El padrón se puede <b>simular</b> antes de autorizar: quien firma ve a cuántas cuentas alcanza
 * antes de que se mueva un solo vencimiento.
 *
 * <p><b>Los roles son los que identity emite.</b> Estos endpoints exigían {@code RISK_MANAGER}, un
 * rol que no existe en ningún sitio del monorepo: identity nunca lo ha emitido y su catálogo de
 * capacidades no lo conoce. El efecto no era un 403 ruidoso sino algo peor — sólo ADMIN podía pasar,
 * así que el maker-checker de un apoyo masivo se resolvía entre dos administradores y la separación
 * por función que el rol nombraba no existía. Un control que nombra a alguien que no existe se lee
 * como un control y no lo es.
 */
@RestController
@RequestMapping("/api/v1/portfolio/relief-programs")
@Tag(name = "Relief Programs", description = "Programas de apoyo por contingencia (maker-checker)")
class ReliefProgramController {

    private final ManageReliefProgramUseCase programas;

    ReliefProgramController(ManageReliefProgramUseCase programas) { this.programas = programas; }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RISK_ANALYST')")
    @Operation(summary = "Proponer un programa de apoyo (maker)")
    ResponseEntity<ReliefProgramResponse> proponer(@Valid @RequestBody ProponerRequest req,
                                                   @RequestHeader("X-User-Id") String usuario) {
        ReliefProgram p = programas.proponer(new Proponer(req.name(), req.reason(),
                req.deferredPeriods(), req.validFrom(), req.validTo(), req.productCode(),
                req.originUnitCode(), req.region(), req.maxDaysDelinquent(),
                req.eligibilityCutoffDate(), req.accrualDuringRelief()), usuario);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReliefProgramResponse.de(p));
    }

    @GetMapping("/{programId}/padron")
    @PreAuthorize("hasAnyRole('ADMIN','RISK_ANALYST','COMMITTEE','AUDITOR')")
    @Operation(summary = "Cuántas cuentas alcanzaría, sin tocar ninguna")
    Padron padron(@PathVariable UUID programId) {
        return programas.simularPadron(programId);
    }

    @PostMapping("/{programId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','RISK_ANALYST')")
    @Operation(summary = "Autorizar el programa (checker) — no puede ser quien lo propuso")
    ReliefProgramResponse autorizar(@PathVariable UUID programId,
                                     @RequestHeader("X-User-Id") String usuario) {
        return ReliefProgramResponse.de(programas.autorizar(programId, usuario));
    }

    @PostMapping("/{programId}/grant")
    @PreAuthorize("hasAnyRole('ADMIN','RISK_ANALYST')")
    @Operation(summary = "Otorgar: corre los vencimientos de todo el padrón")
    ResponseEntity<Otorgamiento> otorgar(@PathVariable UUID programId) {
        return ResponseEntity.accepted().body(new Otorgamiento(programId, programas.otorgar(programId)));
    }

    record ProponerRequest(@NotBlank String name,
                           @NotBlank String reason,
                           @Positive int deferredPeriods,
                           @NotNull LocalDate validFrom,
                           @NotNull LocalDate validTo,
                           String productCode,
                           String originUnitCode,
                           String region,
                           Integer maxDaysDelinquent,
                           @NotNull LocalDate eligibilityCutoffDate,
                           String accrualDuringRelief) {}

    record Otorgamiento(UUID programId, int cuentasInscritas) {}

    record ReliefProgramResponse(UUID reliefProgramId, String name, String reason,
                                 int deferredPeriods, LocalDate validFrom, LocalDate validTo,
                                 String status, String proposedBy, String approvedBy) {

        static ReliefProgramResponse de(ReliefProgram p) {
            return new ReliefProgramResponse(p.getId(), p.getName(), p.getReason(),
                    p.getDeferredPeriods(), p.getValidFrom(), p.getValidTo(), p.getStatus(),
                    p.getProposedBy(), p.getApprovedBy());
        }
    }
}
