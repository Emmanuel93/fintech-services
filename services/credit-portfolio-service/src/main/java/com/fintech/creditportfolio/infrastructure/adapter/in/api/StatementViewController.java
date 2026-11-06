package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.in.StatementViewUseCase;
import com.fintech.creditportfolio.application.port.in.StatementViewUseCase.StatementView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * La vista del estado de cuenta: lo que el servicio de estados de cuenta lee por cada cuenta que corta.
 */
@RestController
@RequestMapping("/api/v1/portfolio/accounts")
@Tag(name = "Statement view", description = "Datos de un período para el estado de cuenta")
class StatementViewController {

    private final StatementViewUseCase statements;

    StatementViewController(StatementViewUseCase statements) {
        this.statements = statements;
    }

    @GetMapping("/{creditAccountId}/statement-view")
    @Operation(summary = "Cuenta, libro del período, plan y colocaciones para armar un estado de cuenta")
    StatementView view(@PathVariable UUID creditAccountId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueUntil) {
        return statements.view(creditAccountId, from, to, dueUntil);
    }
}
