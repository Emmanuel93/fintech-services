package com.fintech.disbursement.infrastructure.adapter.out.banking;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.port.out.PayoutRouteResolverPort;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Le pregunta a tesorería por dónde sale el pago.
 *
 * <p><b>Distingue tres desenlaces y no dos.</b> Un 404 —«ninguna ruta aplica»— es un hueco de
 * configuración que tiene que doler y gastar intento. Un timeout o un 5xx es una indisponibilidad
 * pasajera: tratarla como configuración incompleta haría que una caída de minutos agotara los
 * intentos de órdenes perfectamente válidas y las marcara {@code FAILED}. Dinero real.
 */
@Component
class BankingPayoutRouteAdapter implements PayoutRouteResolverPort {

    private static final Logger log = LoggerFactory.getLogger(BankingPayoutRouteAdapter.class);

    private final RestClient rest;

    BankingPayoutRouteAdapter(DisbursementProperties properties) {
        this.rest = RestClient.builder()
                .baseUrl(properties.getBanking().getBaseUrl())
                .build();
    }

    @Override
    public Optional<PayoutRoute> resolve(UUID companyId, Rail rail, BigDecimal amount) {
        try {
            RouteResponse r = rest.post()
                    .uri("/api/v1/payouts/route")
                    // Header-trust: tesorería exige rol SERVICE para devolver la CLABE completa.
                    .header("X-User-Id", "disbursement-service")
                    .header("X-Roles", "SERVICE")
                    .body(Map.of("companyId", companyId, "rail", rail.name(), "amount", amount))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        // No se lanza: el 4xx es la respuesta legítima «no hay ruta».
                    })
                    .body(RouteResponse.class);

            if (r == null || r.orderingClabe() == null) {
                log.warn("Tesorería no encontró ruta empresa={} rail={} monto={}", companyId, rail, amount);
                return Optional.empty();
            }
            return Optional.of(new PayoutRoute(r.bankAccountId(), r.orderingClabe(),
                    r.orderingHolderName(), r.orderingTaxId(), r.providerClientRef(),
                    Provider.valueOf(r.provider())));

        } catch (RestClientException e) {
            throw new PayoutRoutingUnavailableException(
                    "Tesorería no responde al resolver la ruta de pago: " + e.getMessage(), e);
        }
    }

    /** La CLABE llega completa y se queda dentro del proceso: nunca entra a una bitácora. */
    private record RouteResponse(UUID routeId,
                                 UUID bankAccountId,
                                 String orderingClabe,
                                 String orderingHolderName,
                                 String orderingTaxId,
                                 String providerClientRef,
                                 String institutionCode,
                                 String rail,
                                 String provider) {}
}
