package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.application.service.UIFReportService;
import com.fintech.audit.domain.UIFReportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Una coincidencia confirmada de listas <b>deja constancia</b>.
 *
 * <h2>Por qué esto tiene que existir</h2>
 *
 * Confirmar que alguien está en una lista de sancionados no termina en «rechazar al cliente»:
 * genera una <b>obligación de reporte</b>. El agregado {@code UIFReport} existía desde hacía
 * tiempo y <b>no tenía un solo escritor</b> — sólo se leía. Es decir: el control detenía la
 * operación y <b>no dejaba registro de por qué</b>, que es justo la mitad que una revisión pide.
 *
 * <p>Dicho de otra forma: un control que bloquea sin dejar constancia protege a la institución del
 * dinero y no de la auditoría.
 *
 * <h2>Las dos escrituras, y por qué son dos</h2>
 *
 * <ul>
 *   <li>La <b>bitácora</b> ({@code AuditService}) guarda el hecho crudo con su payload: qué llegó,
 *       cuándo y de quién. Es el rastro técnico.</li>
 *   <li>El <b>reporte UIF</b> es el expediente regulatorio: nace {@code PENDING} porque alguien
 *       tiene que presentarlo, y mientras lo esté es trabajo visible en vez de un hueco.</li>
 * </ul>
 *
 * <h2>Y por qué INTERNO y no RELEVANTE</h2>
 *
 * Porque el tipo regulatorio de un reporte lo decide cumplimiento mirando el caso, no un listener
 * mirando un evento. {@code INTERNO} es el que no afirma nada que no se haya determinado: queda
 * abierto y quien lo trabaje lo reclasifica. Nacer como {@code RELEVANTE} sería que un consumidor de
 * Kafka tipificara un reporte ante la autoridad, que no es su decisión.
 */
@Component
public class ComplianceEventListener {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEventListener.class);
    private static final String SOURCE = "compliance-service";

    private final AuditService auditService;
    private final UIFReportService uifReports;

    public ComplianceEventListener(AuditService auditService, UIFReportService uifReports) {
        this.auditService = auditService;
        this.uifReports   = uifReports;
    }

    @KafkaListener(topics = "compliance.confirmed-match",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onConfirmedMatch(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String verificationId = EventPayloadParser.field(p, "verificationId", "prospectId");
        String partyId        = EventPayloadParser.field(p, "partyId", "prospectId");

        auditService.record("COMPLIANCE_CONFIRMED_MATCH", SOURCE, verificationId, partyId, null, payload);

        UUID partyUuid = parse(partyId);
        if (partyUuid == null) {
            // Sin persona no se puede abrir expediente, pero el hecho ya quedó en la bitácora: se
            // grita y no se tira. Perder la constancia por no poder abrir el reporte sería cambiar
            // un registro incompleto por ninguno.
            log.error("COMPLIANCE_CONFIRMED_MATCH sin partyId utilizable (verificación {}) — queda en "
                      + "bitácora SIN reporte UIF. Hay que abrirlo a mano.", verificationId);
            return;
        }

        uifReports.create(partyUuid, UIFReportType.INTERNO,
                "COMPLIANCE_CONFIRMED_MATCH", verificationId, notesOf(p));

        log.warn("Coincidencia CONFIRMADA de listas partyId={} verificación={} — reporte UIF abierto",
                partyUuid, verificationId);
    }

    /**
     * La nota del reporte lleva <b>lo que decidió la persona que adjudicó</b>, no un texto genérico.
     *
     * <p>Es lo único que explica el expediente meses después: quién firmó, contra qué programa y con
     * qué argumento. Un reporte que sólo dice «coincidencia confirmada» obliga a reconstruirlo.
     */
    private static String notesOf(Map<String, Object> p) {
        String listedName   = EventPayloadParser.field(p, "listedName", "listedName");
        String program  = EventPayloadParser.field(p, "program", "program");
        String decidedBy = EventPayloadParser.field(p, "decidedBy", "decidedBy");
        String reasoning = EventPayloadParser.field(p, "notes", "notes");

        StringBuilder sb = new StringBuilder("Coincidencia de listas confirmada por cumplimiento.");
        if (listedName   != null) sb.append(" Entrada de lista: ").append(listedName).append('.');
        if (program  != null) sb.append(" Programa: ").append(program).append('.');
        if (decidedBy != null) sb.append(" Adjudicó: ").append(decidedBy).append('.');
        if (reasoning != null) sb.append(" Argumento: ").append(reasoning);
        return sb.toString();
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
