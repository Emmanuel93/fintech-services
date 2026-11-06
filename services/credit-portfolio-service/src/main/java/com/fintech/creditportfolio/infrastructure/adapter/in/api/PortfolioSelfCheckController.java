package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.service.PortfolioSelfCheckService;
import com.fintech.shared.selfcheck.SelfCheckReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portfolio")
@Tag(name = "Portfolio self-checks", description = "La cartera se revisa a sí misma con sus propios datos")
class PortfolioSelfCheckController {

    private final PortfolioSelfCheckService selfChecks;

    PortfolioSelfCheckController(PortfolioSelfCheckService selfChecks) {
        this.selfChecks = selfChecks;
    }

    @GetMapping("/self-checks")
    @Operation(summary = "Autoverificación de la cartera: deuda igual a sus movimientos, fotos diarias sin brecha, "
            + "cierres del día terminados, saldos no negativos; informa disposiciones sin desenlace")
    SelfCheckReport selfChecks(@RequestParam(required = false) String period) {
        return selfChecks.check(period);
    }
}
