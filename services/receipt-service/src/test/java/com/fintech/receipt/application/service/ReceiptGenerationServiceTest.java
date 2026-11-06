package com.fintech.receipt.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.documents.DocumentModel;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.port.out.ReceiptDocumentStore;
import com.fintech.receipt.application.port.out.ReceiptEventPublisher;
import com.fintech.receipt.application.port.out.ReceiptRenderer;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.application.port.out.ReceiptSource;
import com.fintech.receipt.application.service.assembly.ReceiptAssembler;
import com.fintech.receipt.application.service.assembly.ReceiptInputs;
import com.fintech.receipt.domain.CutoffFacts;
import com.fintech.receipt.support.ReceiptSamples;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptGenerationServiceTest {

    final ReceiptRepository receipts = mock(ReceiptRepository.class);
    final ReceiptSource source = mock(ReceiptSource.class);
    final ReceiptRenderer renderer = mock(ReceiptRenderer.class);
    final ReceiptDocumentStore documents = mock(ReceiptDocumentStore.class);
    final ReceiptEventPublisher publisher = mock(ReceiptEventPublisher.class);
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    ReceiptGenerationService service;

    final UUID line = UUID.randomUUID(), her = UUID.randomUUID();
    final UUID ana = UUID.randomUUID(), beto = UUID.randomUUID(), carla = UUID.randomUUID();
    final Map<UUID, UUID> registered = new HashMap<>();

    @BeforeEach
    void setUp() {
        BusinessClock clock = new BusinessClock(Clock.fixed(Instant.parse("2025-10-22T12:00:00Z"), ZoneId.of("America/Mexico_City")));
        service = new ReceiptGenerationService(receipts, source, new ReceiptAssembler(), renderer, documents, publisher,
                new ReceiptProperties(), json, clock);
        when(renderer.render(any(DocumentModel.class))).thenReturn(new byte[]{1, 2, 3});
        when(renderer.templateVersion()).thenReturn("recibo-pago-distribuidora@test");
        when(receipts.register(any(), any())).thenAnswer(inv -> registered.computeIfAbsent(inv.getArgument(1), k -> UUID.randomUUID()));
        when(receipts.claim(any(), any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            UUID client = registered.entrySet().stream().filter(e -> e.getValue().equals(id)).findFirst().orElseThrow().getKey();
            return Optional.of(new ReceiptRepository.Claim(id, ReceiptSamples.facts(line, her, "DISTRIBUTOR_LINE"), client, 1));
        });
        when(source.view(eq(line), any(), any(), any())).thenReturn(ReceiptSamples.view(line, her, ana, beto, carla));
        when(source.references(line)).thenReturn(Map.of(ana, 1234566L, beto, 1234574L, carla, 1234582L));
        when(source.parties(anyCollection())).thenReturn(Map.of(
                ana, new ReceiptSource.PartySummary(ana, "Ana Torres", null),
                beto, new ReceiptSource.PartySummary(beto, "Beto Ruiz", "RUBE800101AB1"),
                her, new ReceiptSource.PartySummary(her, "Laura Méndez Ortiz", null)));
        when(source.profiles(anyCollection())).thenReturn(Map.of(
                ana, new ReceiptSource.ClientProfile(ana, "NUEVO", List.of(), 2, 0, 2),
                beto, new ReceiptSource.ClientProfile(beto, "REVOLVENTE", List.of(), 2, 1, 1)));
    }

    @Test
    @DisplayName("un recibo por cliente con algo facturado; su suma es la fila «Pagos de tus clientes» del estado")
    void oneReceiptPerBilledClient_andTheySumToTheClientsPayments() {
        var outcomes = service.onCutoffClosed(ReceiptSamples.facts(line, her, "DISTRIBUTOR_LINE"));

        assertThat(outcomes).containsOnlyKeys(ana, beto);   // Carla's loan bills nothing yet
        assertThat(outcomes.values()).containsOnly(ReceiptGenerationService.Outcome.GENERATED);
        ArgumentCaptor<ReceiptRepository.Generated> generated = ArgumentCaptor.forClass(ReceiptRepository.Generated.class);
        verify(receipts, times(2)).markGenerated(any(), generated.capture());
        BigDecimal receiptsTotal = generated.getAllValues().stream().map(ReceiptRepository.Generated::totalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // The statement's row: the sum of every live placement's billed installments (ReceiptSamples.view).
        BigDecimal clientsPayments = ReceiptSamples.view(line, her, ana, beto, carla).placementsOrEmpty().stream()
                .filter(ReceiptSource.PlacementLine::live)
                .flatMap(p -> p.dueOrEmpty().stream()).map(ReceiptSource.InstallmentLine::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receiptsTotal).isEqualByComparingTo(clientsPayments);

        var betos = generated.getAllValues().stream().filter(g -> "Beto Ruiz".equals(g.clientName())).findFirst().orElseThrow();
        assertThat(betos.paymentReference()).isEqualTo(1234574L);
        assertThat(betos.clabe()).isEqualTo("646180157000000004");
        assertThat(betos.clientType()).isEqualTo("REVOLVENTE");
        assertThat(betos.liveLoans()).isEqualTo(1);
        assertThat(betos.folio()).isEqualTo("RC-20251021-1234574");
        verify(documents, times(2)).store(any(), any(), eq("application/pdf"));
        // The view and its people are read once for the whole line.
        verify(source, times(1)).view(any(), any(), any(), any());
        verify(source, times(1)).profiles(anyCollection());
    }

    @Test
    @DisplayName("la foto guardada basta para volver a imprimir el recibo")
    void theSnapshotIsEnoughToPrintAgain() throws Exception {
        service.onCutoffClosed(ReceiptSamples.facts(line, her, "DISTRIBUTOR_LINE"));
        ArgumentCaptor<ReceiptRepository.Generated> generated = ArgumentCaptor.forClass(ReceiptRepository.Generated.class);
        verify(receipts, times(2)).markGenerated(any(), generated.capture());
        ReceiptInputs back = json.readValue(generated.getAllValues().getFirst().snapshotJson(), ReceiptInputs.class);
        assertThat(back.totalDue()).isEqualByComparingTo(generated.getAllValues().getFirst().totalDue());
        assertThat(back.cutoff().cycleNumber()).isEqualTo(21);
    }

    @Test
    @DisplayName("una línea que no paga a una distribuidora no da recibos; un recibo que falla queda FAILED")
    void otherProductsGiveNoReceipts_andAFailureIsKept() {
        assertThat(service.onCutoffClosed(ReceiptSamples.facts(line, her, "PERSONAL_LOAN"))).isEmpty();
        verify(receipts, never()).register(any(), any());

        when(renderer.render(any(DocumentModel.class))).thenThrow(new IllegalStateException("la plantilla no compila"));
        var outcomes = service.onCutoffClosed(ReceiptSamples.facts(line, her, "DISTRIBUTOR_LINE"));
        assertThat(outcomes.values()).containsOnly(ReceiptGenerationService.Outcome.FAILED);
        verify(receipts, times(2)).markFailed(any(), eq("IllegalStateException: la plantilla no compila"));
    }

    @Test
    @DisplayName("si otra réplica ya lo tiene, no se genera dos veces")
    void aReceiptTakenElsewhereIsNotGeneratedTwice() {
        doReturn(Optional.empty()).when(receipts).claim(any(), any(), any());
        var outcomes = service.onCutoffClosed(ReceiptSamples.facts(line, her, "DISTRIBUTOR_LINE"));
        assertThat(outcomes.values()).containsOnly(ReceiptGenerationService.Outcome.ALREADY_TAKEN);
        verify(renderer, never()).render(any());
    }
}
