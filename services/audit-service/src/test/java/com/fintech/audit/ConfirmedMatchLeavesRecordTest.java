package com.fintech.audit;

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.application.service.UIFReportService;
import com.fintech.audit.domain.UIFReportType;
import com.fintech.audit.infrastructure.adapter.in.messaging.ComplianceEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Una coincidencia confirmada de listas <b>deja constancia</b>.
 *
 * <p>Era decisión tomada —«deja constancia»— y no estaba construida: {@code UIFReport} existía y
 * <b>nadie lo escribía</b>. El control detenía la operación y no registraba por qué, con lo que
 * protegía a la institución del dinero y no de la auditoría.
 *
 * <p>Lo que estas pruebas fijan es que el reporte se abra, que lleve <b>el argumento de quien
 * adjudicó</b> —lo único que explica el expediente meses después— y que un evento mutilado no se
 * trague el hecho en silencio.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmedMatchLeavesRecordTest {

    @Mock AuditService auditService;
    @Mock UIFReportService uifReports;

    @Captor ArgumentCaptor<String> notesCaptor;

    private ComplianceEventListener listener() {
        return new ComplianceEventListener(auditService, uifReports);
    }

    // ── La constancia se abre ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una coincidencia confirmada abre un reporte UIF, además de la bitácora")
    void aconfirmedMatchOpensAuifReport() {
        UUID party = UUID.randomUUID();
        UUID verificationId = UUID.randomUUID();
        String payload = """
                {"verificationId":"%s","partyId":"%s","prospectId":"%s",
                 "listedName":"JUAN PEREZ GARCIA","program":"SDN",
                 "decidedBy":"cumplimiento@kredius.mx",
                 "notes":"Misma CURP y misma fecha de nacimiento que la entrada de OFAC"}
                """.formatted(verificationId, party, UUID.randomUUID());

        listener().onConfirmedMatch(payload);

        // Las dos escrituras: el rastro técnico y el expediente regulatorio.
        verify(auditService).record(eq("COMPLIANCE_CONFIRMED_MATCH"), eq("compliance-service"),
                eq(verificationId.toString()), eq(party.toString()), any(), eq(payload));
        verify(uifReports).create(eq(party), eq(UIFReportType.INTERNO),
                eq("COMPLIANCE_CONFIRMED_MATCH"), eq(verificationId.toString()), any());
    }

    @Test
    @DisplayName("el reporte lleva el ARGUMENTO de quien adjudicó, no un texto genérico")
    void thereportCarriesTheAdjudicatorsReasoning() {
        // Es lo único que explica el expediente después. Un reporte que sólo dijera «coincidencia
        // confirmada» obligaría a reconstruir a mano por qué se confirmó y quién lo decidió.
        String payload = """
                {"verificationId":"%s","partyId":"%s","prospectId":"%s",
                 "listedName":"JUAN PEREZ GARCIA","program":"SDN",
                 "decidedBy":"cumplimiento@kredius.mx",
                 "notes":"Misma CURP y misma fecha de nacimiento que la entrada de OFAC"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        listener().onConfirmedMatch(payload);

        verify(uifReports).create(any(), any(), any(), any(), notesCaptor.capture());
        assertThat(notesCaptor.getValue())
                .contains("JUAN PEREZ GARCIA")
                .contains("SDN")
                .contains("cumplimiento@kredius.mx")
                .contains("Misma CURP");
    }

    @Test
    @DisplayName("nace INTERNO: tipificar un reporte ante la autoridad no es decisión de un listener")
    void itisOpenedAsInternal() {
        // El tipo regulatorio lo decide cumplimiento mirando el caso. INTERNO es el que no afirma
        // nada que no se haya determinado: queda abierto y quien lo trabaje lo reclasifica.
        String payload = """
                {"verificationId":"%s","partyId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        listener().onConfirmedMatch(payload);

        verify(uifReports).create(any(), eq(UIFReportType.INTERNO), any(), any(), any());
    }

    // ── Lo que pasa cuando el evento llega mal ───────────────────────────────────────────────

    @Test
    @DisplayName("sin partyId utilizable el hecho SIGUE en bitácora, aunque no se pueda abrir reporte")
    void anunusablePartyIdStillLeavesTheAuditTrail() {
        // Perder la constancia por no poder abrir el expediente sería cambiar un registro incompleto
        // por ninguno. Se registra el hecho, se grita, y queda para abrir a mano.
        String payload = """
                {"verificationId":"%s","partyId":"no-es-un-uuid"}
                """.formatted(UUID.randomUUID());

        listener().onConfirmedMatch(payload);

        verify(auditService).record(eq("COMPLIANCE_CONFIRMED_MATCH"), any(), any(), any(), any(), any());
        verify(uifReports, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("si falta partyId se cae al prospecto: la persona es la misma")
    void itfallsBackToTheProspect() {
        // El veredicto puede emitirse antes de que exista el Party —los dos cuelgan del mismo evento
        // de alta— y el prospecto es la llave que los dos comparten.
        UUID prospectId = UUID.randomUUID();
        String payload = """
                {"verificationId":"%s","prospectId":"%s"}
                """.formatted(UUID.randomUUID(), prospectId);

        listener().onConfirmedMatch(payload);

        verify(uifReports).create(eq(prospectId), any(), any(), any(), any());
    }
}
