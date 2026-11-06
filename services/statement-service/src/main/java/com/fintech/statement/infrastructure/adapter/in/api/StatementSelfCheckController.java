package com.fintech.statement.infrastructure.adapter.in.api;

import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.statement.application.service.StatementSelfCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/statements")
@Tag(name = "Statement self-checks", description = "Los estados de cuenta se revisan a sí mismos con sus propios datos")
class StatementSelfCheckController {

    private final StatementSelfCheckService selfChecks;

    StatementSelfCheckController(StatementSelfCheckService selfChecks) {
        this.selfChecks = selfChecks;
    }

    @GetMapping("/self-checks")
    @Operation(summary = "Autoverificación de los estados de cuenta: un estado por cuenta cerrada en cada corte, el saldo "
            + "de su corte, su documento íntegro y ninguno sin generar")
    SelfCheckReport selfChecks() {
        return selfChecks.check();
    }
}
