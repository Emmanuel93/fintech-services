package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.port.in.ManagePayoutRoutesUseCase;
import com.fintech.banking.application.port.in.ManagePayoutRoutesUseCase.AltaDeRuta;
import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase;
import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase.Peticion;
import com.fintech.banking.domain.PayoutDecision;
import com.fintech.banking.domain.PayoutProvider;
import com.fintech.banking.domain.PayoutRail;
import com.fintech.banking.domain.PayoutRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class PayoutRouteController {

    private final ResolvePayoutRouteUseCase resolver;
    private final ManagePayoutRoutesUseCase admin;

    PayoutRouteController(ResolvePayoutRouteUseCase resolver, ManagePayoutRoutesUseCase admin) {
        this.resolver = resolver;
        this.admin    = admin;
    }

    /**
     * «¿Por dónde sale este pago?» — la llama el orquestador de payouts al crear la orden.
     *
     * <p><b>La respuesta lleva la CLABE completa</b>, y es el único sitio del servicio donde sale:
     * el conector la necesita para armar la cadena original. Por eso el rol es {@code SERVICE} y no
     * uno de consulta.
     */
    @PostMapping("/payouts/route")
    PayoutDecision resolver(@Valid @RequestBody ResolveRouteRequest req) {
        return resolver.resolver(new Peticion(req.companyId(), req.rail(), req.amount()));
    }

    @PostMapping("/payout-routes")
    ResponseEntity<PayoutRouteResponse> alta(@Valid @RequestBody CreatePayoutRouteRequest req) {
        PayoutRoute r = admin.alta(new AltaDeRuta(req.companyId(), req.rail(), req.provider(),
                req.bankAccountId(), req.minAmount(), req.maxAmount(), req.priority()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PayoutRouteResponse.de(r));
    }

    @GetMapping("/payout-routes")
    List<PayoutRouteResponse> listar() {
        return admin.listar().stream().map(PayoutRouteResponse::de).toList();
    }

    @DeleteMapping("/payout-routes/{id}")
    PayoutRouteResponse deshabilitar(@PathVariable UUID id) {
        return PayoutRouteResponse.de(admin.deshabilitar(id));
    }

    record ResolveRouteRequest(UUID companyId,
                               @NotNull PayoutRail rail,
                               @NotNull @Positive BigDecimal amount) {}

    record CreatePayoutRouteRequest(UUID companyId,
                                    @NotNull PayoutRail rail,
                                    @NotNull PayoutProvider provider,
                                    @NotNull UUID bankAccountId,
                                    BigDecimal minAmount,
                                    BigDecimal maxAmount,
                                    Integer priority) {}
}
