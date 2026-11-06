package com.fintech.statement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.shared.time.BusinessClock;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementDocumentStore;
import com.fintech.statement.application.port.out.StatementRenderer;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.service.StatementRegenerationService.Outcome;
import com.fintech.statement.application.service.assembly.DistributorStatementAssembler;
import com.fintech.documents.DocumentModel;
import com.fintech.documents.Formats;
import com.fintech.statement.application.service.assembly.InstallmentStatementAssembler;
import com.fintech.statement.application.service.assembly.RevolvingStatementAssembler;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.Statement;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementStatus;
import com.fintech.statement.domain.StatementTemplate;
import com.fintech.statement.support.Samples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A statement already issued is produced again from the snapshot it was built with: same figures, same
 * folio, same issue date; only the template (and the names, asked again) may differ.
 */
class StatementRegenerationServiceTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final Instant EMITIDO = Instant.parse("2026-12-01T06:05:00Z");

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final StatementRepository statements = mock(StatementRepository.class);
    private final StatementSource source = mock(StatementSource.class);
    private final StatementRenderer renderer = mock(StatementRenderer.class);
    private final StatementDocumentStore documents = mock(StatementDocumentStore.class);
    private final StatementRegenerationService service = new StatementRegenerationService(statements, source,
            renderer, documents, new StatementProperties(), json, BusinessClock.of(MX),
            List.of(new InstallmentStatementAssembler(), new RevolvingStatementAssembler(), new DistributorStatementAssembler()));

    @Test
    @DisplayName("se vuelve a producir desde la foto, con la fecha de emisión original, y sin notificar a nadie")
    void regeneratesFromTheSnapshot() throws Exception {
        UUID id = UUID.randomUUID(), cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 3, "16666.66", "2142.27", null);
        SourceView view = Samples.plazo(cuenta, titular);
        when(statements.findById(id)).thenReturn(Optional.of(generated(id, f)));
        // The snapshot keeps the business-time issue instant; generated_at (real time) is a day later here.
        Instant emitidoNegocio = Instant.parse("2026-11-30T20:26:00Z");
        when(statements.findSnapshot(id)).thenReturn(Optional.of(json.writeValueAsString(
                Map.of("cutoff", f, "view", view, "issuedAt", emitidoNegocio))));
        when(statements.previousBalance(cuenta, 3)).thenReturn(Optional.of(new BigDecimal("18808.93")));
        when(source.parties(anyCollection())).thenReturn(Map.of(titular, Samples.persona(titular, "Irene Ibarra Cárdenas")));
        when(renderer.render(eq(StatementTemplate.PERSONAL_LOAN), any())).thenReturn("pdf-nuevo".getBytes());
        when(renderer.templateVersion(StatementTemplate.PERSONAL_LOAN)).thenReturn("estado-cuenta-prestamo-personal@abc123");

        Outcome outcome = service.regenerate(id);

        assertThat(outcome).isEqualTo(Outcome.REGENERATED);
        ArgumentCaptor<DocumentModel> model = ArgumentCaptor.forClass(DocumentModel.class);
        verify(renderer).render(eq(StatementTemplate.PERSONAL_LOAN), model.capture());
        Map<String, Object> p = model.getValue().parameters();
        assertThat(p.get("HOLDER_NAME")).isEqualTo("Irene Ibarra Cárdenas");
        assertThat(p.get("GENERATED_AT")).as("la fecha de emisión es la de la foto (tiempo de negocio), no la de hoy")
                .isEqualTo(Formats.stamp(emitidoNegocio, MX));
        assertThat(p.get("STATEMENT_FOLIO")).asString().startsWith("EC-20261130-");
        verify(documents).store(eq(id), eq("pdf-nuevo".getBytes()), eq("application/pdf"));
        verify(statements).markRegenerated(id, "estado-cuenta-prestamo-personal@abc123", "pdf-nuevo".length());
        verify(statements, never()).markGenerated(any(), any());
        verify(statements, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("una foto sin fecha de emisión (anterior a que se guardara) usa la generated_at del registro")
    void olderSnapshotsFallBackToGeneratedAt() throws Exception {
        UUID id = UUID.randomUUID(), cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 3, "16666.66", "2142.27", null);
        when(statements.findById(id)).thenReturn(Optional.of(generated(id, f)));
        when(statements.findSnapshot(id)).thenReturn(Optional.of(json.writeValueAsString(
                Map.of("cutoff", f, "view", Samples.plazo(cuenta, titular)))));
        when(statements.previousBalance(any(), anyInt())).thenReturn(Optional.empty());
        when(source.parties(anyCollection())).thenReturn(Map.of());
        when(renderer.render(any(), any())).thenReturn("pdf".getBytes());
        when(renderer.templateVersion(any())).thenReturn("v");

        assertThat(service.regenerate(id)).isEqualTo(Outcome.REGENERATED);
        ArgumentCaptor<DocumentModel> model = ArgumentCaptor.forClass(DocumentModel.class);
        verify(renderer).render(eq(StatementTemplate.PERSONAL_LOAN), model.capture());
        assertThat(model.getValue().parameters().get("GENERATED_AT")).isEqualTo(Formats.stamp(EMITIDO, MX));
        assertThat(model.getValue().parameters().get("HOLDER_NAME")).isEqualTo("Titular de la cuenta");
    }

    @Test
    @DisplayName("si la plantilla truena, el documento anterior se queda y el estado no se marca fallido")
    void keepsTheOldDocumentWhenRenderingFails() throws Exception {
        UUID id = UUID.randomUUID(), cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "CREDIT_CARD", "REVOLVING", "CUSTOMER", 1, "9500", "1200", "475");
        when(statements.findById(id)).thenReturn(Optional.of(generated(id, f)));
        when(statements.findSnapshot(id)).thenReturn(Optional.of(json.writeValueAsString(
                Map.of("cutoff", f, "view", Samples.revolvente(cuenta, titular)))));
        when(statements.previousBalance(any(), anyInt())).thenReturn(Optional.empty());
        when(source.parties(anyCollection())).thenReturn(Map.of());
        when(renderer.render(any(), any())).thenThrow(new IllegalStateException("plantilla rota"));

        assertThat(service.regenerate(id)).isEqualTo(Outcome.FAILED);
        verify(documents, never()).store(any(), any(), anyString());
        verify(statements, never()).markRegenerated(any(), anyString(), anyInt());
        verify(statements, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("un estado que no está generado, o sin foto, se salta")
    void skipsWhatCannotBeRegenerated() {
        UUID pendiente = UUID.randomUUID(), sinFoto = UUID.randomUUID(), cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 1, "1", "1", null);
        when(statements.findById(pendiente)).thenReturn(Optional.of(withStatus(generated(pendiente, f), StatementStatus.PENDING)));
        when(statements.findById(sinFoto)).thenReturn(Optional.of(generated(sinFoto, f)));
        when(statements.findSnapshot(sinFoto)).thenReturn(Optional.empty());

        assertThat(service.regenerate(pendiente)).isEqualTo(Outcome.SKIPPED);
        assertThat(service.regenerate(sinFoto)).isEqualTo(Outcome.SKIPPED);
        assertThat(service.regenerate(UUID.randomUUID())).isEqualTo(Outcome.SKIPPED);
        verify(renderer, never()).render(any(), any());
    }

    private static Statement generated(UUID id, CutoffFacts f) {
        return new Statement(id, f.creditAccountId(), f.cycleNumber(), f.runId(), f.obligorPartyId(), f.holderKind(),
                f.productType(), f.productBehavior(), StatementLayout.INSTALLMENT, f.periodStart(), f.cutoffDate(),
                f.paymentDueDate(), f.balanceAtCutoff(), f.amountDue(), f.minimumPayment(), StatementStatus.GENERATED,
                1, null, "CTR-202609-0101", 1234L, "estado-cuenta-prestamo-personal@000000", "pod-1", EMITIDO,
                EMITIDO.minusSeconds(60));
    }

    private static Statement withStatus(Statement s, StatementStatus status) {
        return new Statement(s.statementId(), s.creditAccountId(), s.cycleNumber(), s.runId(), s.obligorPartyId(),
                s.holderKind(), s.productType(), s.productBehavior(), s.layout(), s.periodStart(), s.cutoffDate(),
                s.paymentDueDate(), s.balanceAtCutoff(), s.amountDue(), s.minimumPayment(), status, s.attempts(),
                s.lastError(), s.contractNumber(), s.documentBytes(), s.templateVersion(), s.generatedBy(),
                s.generatedAt(), s.createdAt());
    }
}
