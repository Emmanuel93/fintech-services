package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.service.TreasurySelfCheckService;
import com.fintech.shared.selfcheck.SelfCheckReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/treasury")
@Tag(name = "Treasury self-checks", description = "Tesorería se revisa a sí misma con sus propios datos")
class TreasurySelfCheckController {

    private final TreasurySelfCheckService selfChecks;

    TreasurySelfCheckController(TreasurySelfCheckService selfChecks) {
        this.selfChecks = selfChecks;
    }

    @GetMapping("/self-checks")
    @Operation(summary = "Autoverificación de tesorería: fondeo contra sus movimientos, intereses a su tasa, órdenes "
            + "liquidadas en caja y en su traza, caja nunca en negativo; informa pagos detenidos")
    SelfCheckReport selfChecks() {
        return selfChecks.check();
    }
}
