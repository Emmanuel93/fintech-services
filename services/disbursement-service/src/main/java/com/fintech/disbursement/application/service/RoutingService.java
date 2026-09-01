package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.port.out.PayoutRouteResolverPort;
import com.fintech.disbursement.domain.OperatingWindow;
import com.fintech.disbursement.domain.Rail;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Decide <b>momento</b>, y le pregunta a tesorería por el <b>destino</b>.
 *
 * <p>Antes decidía las dos cosas, y la mitad que decidía mal era la del destino: elegía el
 * <b>proveedor</b> desde {@code routing_rules} y dejaba la <b>cuenta</b> al conector, que la
 * resolvía con un {@code is_default} por empresa. Media decisión tomada por cada uno y la
 * responsabilidad entera de ninguno.
 *
 * <p>Lo que se queda aquí es la ventana operativa, que sí es de este servicio: cuándo se puede
 * entregar al conector no depende de qué cuenta se use.
 */
@Service
public class RoutingService {

    private final PayoutRouteResolverPort tesoreria;
    private final DisbursementProperties properties;
    private final Clock clock;

    public RoutingService(PayoutRouteResolverPort tesoreria, DisbursementProperties properties, Clock clock) {
        this.tesoreria = tesoreria;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * La decisión completa: <b>cuenta ordenante, rail y proveedor</b>.
     *
     * <p>Vacío si ninguna ruta cubre — el llamador decide qué hacer, porque un hueco de
     * configuración no debería tirar dinero real al DLT. Si tesorería no responde, propaga
     * {@link PayoutRouteResolverPort.PayoutRoutingUnavailableException}: es un desenlace distinto y
     * confundirlo con «no hay ruta» haría que una caída de minutos marcara órdenes válidas como
     * fallidas.
     */
    public Optional<PayoutRouteResolverPort.PayoutRoute> findRoute(UUID companyId, Rail rail,
                                                                   BigDecimal amount) {
        return tesoreria.resolve(companyId, rail, amount);
    }

    public boolean isRailEnabled(Rail rail) {
        DisbursementProperties.RailConfig config = properties.getRails().get(rail.name());
        return config == null || config.isEnabled();
    }

    public boolean isOpen(Rail rail, Instant moment) {
        return window(rail).isOpenAt(ZonedDateTime.ofInstant(moment, clock.getZone()));
    }

    /** DB-05: fuera de ventana la orden espera aquí; no se rechaza. */
    public Instant nextOpening(Rail rail, Instant moment) {
        return window(rail).nextOpening(ZonedDateTime.ofInstant(moment, clock.getZone())).toInstant();
    }

    private OperatingWindow window(Rail rail) {
        DisbursementProperties.RailConfig config = properties.getRails().get(rail.name());
        DisbursementProperties.Window w = config != null
                ? config.getWindow()
                : new DisbursementProperties.RailConfig().getWindow();
        return new OperatingWindow(w.getStart(), w.getEnd(), w.getZone(), w.getDays());
    }
}
