package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * «¿Por dónde sale este pago?» — la respuesta la da <b>tesorería</b>, no este servicio.
 *
 * <p>Hasta ahora la decisión estaba partida y ninguna mitad la tomaba entera:
 * {@code disbursement.routing_rules} elegía el <b>proveedor</b> y la <b>cuenta</b> la resolvía el
 * conector de STP con un {@code is_default} por empresa, ciego al saldo y al costo. Este puerto
 * junta las dos mitades del lado de quien es dueño de ambas.
 *
 * <p><b>Es un puerto y no una llamada directa</b> por la misma razón que existe el resto de la
 * capa ACL: quien decide por dónde sale el dinero puede cambiar sin que el orquestador se entere.
 */
public interface PayoutRouteResolverPort {

    /**
     * La decisión completa. {@code orderingClabe} y compañía viajan hasta el conector, que las
     * necesita para armar la cadena original — sin ellas tendría que resolver la cuenta contra su
     * propia copia del catálogo, que es de donde venimos.
     */
    record PayoutRoute(UUID bankAccountId,
                       String orderingClabe,
                       String orderingHolderName,
                       String orderingTaxId,
                       String providerClientRef,
                       Provider provider) {}

    /**
     * @return la decisión, o vacío si <b>ninguna ruta aplica</b> — un hueco de configuración
     * @throws PayoutRoutingUnavailableException si tesorería no responde. Es distinto de «no hay
     *         ruta»: uno es configuración incompleta y el otro es una indisponibilidad pasajera, y
     *         confundirlos haría que una caída de minutos marcara órdenes reales como fallidas
     */
    java.util.Optional<PayoutRoute> resolve(UUID companyId, Rail rail, BigDecimal amount);

    /** Tesorería no responde. La orden espera; no gasta intento. */
    class PayoutRoutingUnavailableException extends RuntimeException {
        public PayoutRoutingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
