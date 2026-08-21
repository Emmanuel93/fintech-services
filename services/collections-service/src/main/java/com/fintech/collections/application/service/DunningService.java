package com.fintech.collections.application.service;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.CommunicationHoldRepository;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.DunningStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * La cobranza automática: cinco mensajes, uno por día, y se acaba.
 *
 * <p><b>Por qué cinco y no una cadencia perpetua.</b> El ciclo cubre los primeros cinco días de
 * mora, que es donde el recordatorio todavía funciona porque la causa más probable es el olvido.
 * Pasado eso, el que no pagó no es que no se haya enterado, y seguir escribiéndole solo durante
 * meses no cobra más: satura el canal, entrena a ignorarlo y acumula quejas. De ahí en adelante la
 * gestión es de una persona, que es lo que la mora tardía necesita.
 *
 * <p><b>Qué frena el ciclo.</b> Cualquier freno vivo —promesa, convenio en curso, despacho
 * asignado, freno manual— y también un caso terminal. La consulta de frenos se hace una vez por
 * corrida y no una por caso: es la diferencia entre una consulta y varios miles.
 *
 * <p><b>La ventana CONDUSEF aplica a todo</b>, no sólo a las llamadas. Un WhatsApp automático a las
 * 22:00 es un contacto fuera de horario igual que una llamada.
 */
@Service
@Transactional
public class DunningService {

    private static final Logger log = LoggerFactory.getLogger(DunningService.class);

    private static final List<CaseStatus> ACTIVOS = List.of(CaseStatus.OPEN, CaseStatus.MANAGED, CaseStatus.LEGAL);

    private final CollectionCaseRepository caseRepository;
    private final CommunicationHoldRepository holdRepository;
    private final CollectionsEventPublisher eventPublisher;
    private final CollectionsProperties properties;

    public DunningService(CollectionCaseRepository caseRepository,
                           CommunicationHoldRepository holdRepository,
                           CollectionsEventPublisher eventPublisher,
                           CollectionsProperties properties) {
        this.caseRepository = caseRepository;
        this.holdRepository = holdRepository;
        this.eventPublisher = eventPublisher;
        this.properties     = properties;
    }

    /**
     * Una corrida del ciclo. Devuelve cuántas peticiones de envío se publicaron.
     *
     * <p>No manda nada: publica {@code collections.dunning-requested} y notifications decide canal y
     * texto. Mantener a un solo servicio hablando con los proveedores evita duplicar plantillas,
     * preferencias y opt-outs en dos sitios que se desincronizan.
     */
    public int runDailyCycle() {
        if (!properties.isDunningEnabled()) {
            log.debug("Cadencia automática apagada por configuración");
            return 0;
        }
        if (!dentroDeVentana()) {
            log.warn("Corrida de cadencia fuera de la ventana permitida ({}:00-{}:00); no se envía nada",
                    properties.getContactAllowedHoursStart(), properties.getContactAllowedHoursEnd());
            return 0;
        }

        // Sólo los primeros días importan: fuera del ciclo no hay escalón que mandar.
        List<CollectionCase> candidatos =
                caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(ACTIVOS, 1);

        Set<UUID> silenciados = new HashSet<>(holdRepository.findCaseIdsWithActiveHold(Instant.now()));

        int enviados = 0;
        for (CollectionCase c : candidatos) {
            DunningStep step = DunningStep.forDay(c.getDaysDelinquent());
            if (step == null) continue;                       // ya pasó el ciclo de 5 días
            if (c.getStatus().isTerminal()) continue;
            if (silenciados.contains(c.getCaseId())) {
                log.debug("Caso {} silenciado; no entra en la cadencia", c.getCaseId());
                continue;
            }
            eventPublisher.publishDunningRequested(c, step);
            enviados++;
        }

        log.info("Cadencia: {} peticiones publicadas de {} casos activos", enviados, candidatos.size());
        return enviados;
    }

    /**
     * Agradece un pago que cubrió lo prometido.
     *
     * <p>Se publica aunque el caso siga con saldo: reconocer lo que sí se hizo es lo que hace que la
     * siguiente promesa valga algo.
     */
    public void thank(CollectionCase collectionCase, java.math.BigDecimal amount) {
        eventPublisher.publishPaymentThanks(collectionCase, amount);
    }

    /** La misma ventana que se le exige a un agente. */
    private boolean dentroDeVentana() {
        int hora = LocalTime.now(ZoneId.systemDefault()).getHour();
        return hora >= properties.getContactAllowedHoursStart()
            && hora <  properties.getContactAllowedHoursEnd();
    }
}
