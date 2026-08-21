package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fintech.party.application.service.PartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Fija el ejecutivo que gestiona la cartera del cliente.
 *
 * <p>El ejecutivo <b>rota</b>: vacaciones, bajas, rebalanceo de cargas. Por eso vive aquí, en el
 * maestro de personas, y no en el crédito — reasignarlo no debe tocar un solo asiento contable. La
 * sucursal, que sí es el eje contable, se sella en cartera y no cambia nunca.
 */
@Component
public class PortfolioAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAssignedListener.class);

    private final PartyService partyService;

    public PortfolioAssignedListener(PartyService partyService) {
        this.partyService = partyService;
    }

    @KafkaListener(topics = "sales-org.portfolio-assigned",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "portfolioAssignedListenerContainerFactory")
    public void onMessage(PortfolioAssignedPayload p) {
        if (p.obligorPartyId() == null || p.executiveStaffId() == null) return;
        try {
            partyService.assignExecutive(p.obligorPartyId(), p.executiveStaffId(), null);
            log.info("Ejecutivo asignado partyId={} ejecutivo={} sucursal={}",
                    p.obligorPartyId(), p.executiveStaffId(), p.unitCode());
        } catch (Exception e) {
            // Que el party no exista todavía es posible con entrega at-least-once y consumidores
            // que corren en paralelo. Se registra y no se reintenta en bucle: la reconciliación
            // recoge lo que quede suelto.
            log.warn("No se pudo asignar ejecutivo a {}: {}", p.obligorPartyId(), e.getMessage());
        }
    }
}
