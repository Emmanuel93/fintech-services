package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.disbursement.application.port.out.RoutingRuleRepository;
import com.fintech.disbursement.domain.OperatingWindow;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.domain.RoutingRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Decide proveedor y momento. Las dos decisiones son datos, no código: la primera vive en
 * {@code routing_rules}, la segunda en configuración por rail.
 */
@Service
public class RoutingService {

    private final RoutingRuleRepository rules;
    private final DisbursementProperties properties;
    private final Clock clock;

    public RoutingService(RoutingRuleRepository rules, DisbursementProperties properties, Clock clock) {
        this.rules = rules;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Gana la regla de menor prioridad numérica; a igual prioridad, la específica de empresa sobre
     * la genérica. Devuelve vacío si ninguna cubre — el llamador decide qué hacer, porque un hueco
     * de configuración no debería tirar dinero real al DLT.
     */
    public Optional<Provider> findProvider(UUID companyId, Rail rail, BigDecimal amount) {
        return rules.findAllEnabled().stream()
                .filter(rule -> rule.covers(companyId, rail, amount))
                .min(Comparator.comparingInt(RoutingRule::getPriority)
                        .thenComparingInt(RoutingRule::specificity))
                .map(RoutingRule::providerValue);
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
