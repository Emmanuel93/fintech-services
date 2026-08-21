package com.fintech.salesorg.infrastructure.adapter.in.messaging;

import com.fintech.salesorg.application.service.PortfolioAssignmentService;
import com.fintech.salesorg.infrastructure.adapter.out.messaging.PortfolioAssignedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Reparte la cartera de cada crédito desembolsado.
 *
 * <p>Que esto viva en un consumidor y no en un script es lo que hace <b>imposible</b> que un crédito
 * nazca huérfano. Mientras el reparto fue un paso posterior, bastaba con que alguien no lo corriera
 * —o con que una búsqueda fallara en silencio— para dejar cientos de créditos sin dueño y su
 * contabilidad sin sucursal.
 */
@Component
public class CreditAccountActivatedListener {

    static final String TOPIC_PORTFOLIO_ASSIGNED = "sales-org.portfolio-assigned";

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);

    private final PortfolioAssignmentService assignmentService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CreditAccountActivatedListener(PortfolioAssignmentService assignmentService,
                                           KafkaTemplate<String, Object> kafkaTemplate) {
        this.assignmentService = assignmentService;
        this.kafkaTemplate     = kafkaTemplate;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload p) {
        if (p.creditAccountId() == null) return;

        if (p.originUnitCode() == null || p.originUnitCode().isBlank()) {
            // Sin sucursal no hay rama a la que atribuir, y elegir una al azar sería peor que no
            // asignar: pondría ingreso en una plaza que no colocó el crédito. Se avisa fuerte —esto
            // es cartera que quedará sin dueño— en vez de resolverlo inventando.
            log.error("Crédito {} activado SIN sucursal de origen: su cartera queda sin asignar",
                    p.creditAccountId());
            return;
        }

        // El id del crédito como semilla: el reparto es estable ante reintentos, así que reprocesar
        // el evento no mueve la cartera de manos.
        assignmentService.resolver(p.originUnitCode(), p.creditAccountId()).ifPresentOrElse(
                a -> {
                    var evento = new PortfolioAssignedPayload(
                            UUID.randomUUID().toString(), p.creditAccountId(), p.obligorPartyId(),
                            a.executiveStaffId(), a.unitId(), a.unitCode(), a.nivelEscalado(),
                            p.occurredOn() != null ? p.occurredOn() : Instant.now());
                    kafkaTemplate.send(TOPIC_PORTFOLIO_ASSIGNED, p.creditAccountId().toString(), evento);
                    log.info("Cartera asignada creditAccountId={} ejecutivo={} sucursal={} escalado={}",
                            p.creditAccountId(), a.executiveStaffId(), a.unitCode(), a.nivelEscalado());
                },
                () -> log.error("Ni {} ni su rama tienen a nadie: crédito {} sin dueño",
                        p.originUnitCode(), p.creditAccountId()));
    }
}
