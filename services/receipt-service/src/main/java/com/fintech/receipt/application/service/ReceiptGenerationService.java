package com.fintech.receipt.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.port.out.ReceiptDocumentStore;
import com.fintech.receipt.application.port.out.ReceiptEventPublisher;
import com.fintech.receipt.application.port.out.ReceiptRenderer;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.application.port.out.ReceiptRepository.Claim;
import com.fintech.receipt.application.port.out.ReceiptSource;
import com.fintech.receipt.application.port.out.ReceiptSource.ClientProfile;
import com.fintech.receipt.application.port.out.ReceiptSource.LineView;
import com.fintech.receipt.application.port.out.ReceiptSource.PartySummary;
import com.fintech.receipt.application.port.out.ReceiptSource.PlacementLine;
import com.fintech.receipt.application.service.assembly.ReceiptAssembler;
import com.fintech.receipt.application.service.assembly.ReceiptInputs;
import com.fintech.receipt.domain.CutoffFacts;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Generates the receipts of a distributor's clients when her line's cutoff closes.
 *
 * <p>One message is one line: its view, its clients' references, names and profiles are read once, and each client
 * with something billed in this statement gets his receipt, registered by its natural key (line, cycle, client), claimed
 * and generated. A client with nothing billed (his loan was placed after the last cutoff) gets none. Lines of other
 * products are ignored: they have no clients to give receipts to.
 *
 * <p>A receipt that fails stays FAILED and the retry job takes it again; it rebuilds its line's inputs on its own.
 */
@Service
public class ReceiptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptGenerationService.class);

    private final ReceiptRepository receipts;
    private final ReceiptSource source;
    private final ReceiptAssembler assembler;
    private final ReceiptRenderer renderer;
    private final ReceiptDocumentStore documents;
    private final ReceiptEventPublisher publisher;
    private final ReceiptProperties properties;
    private final ObjectMapper json;
    private final ZoneId zone;

    public ReceiptGenerationService(ReceiptRepository receipts, ReceiptSource source, ReceiptAssembler assembler,
                                    ReceiptRenderer renderer, ReceiptDocumentStore documents,
                                    ReceiptEventPublisher publisher, ReceiptProperties properties, ObjectMapper json,
                                    BusinessClock clock) {
        this.receipts = receipts;
        this.source = source;
        this.assembler = assembler;
        this.renderer = renderer;
        this.documents = documents;
        this.publisher = publisher;
        this.properties = properties;
        this.json = json;
        this.zone = clock.zone();
    }

    public enum Outcome { GENERATED, ALREADY_TAKEN, FAILED }

    /** The line's cutoff closed: one receipt per client billed in this statement. Returns how each went. */
    public Map<UUID, Outcome> onCutoffClosed(CutoffFacts facts) {
        if (!facts.paysADistributor()) return Map.of();
        LineInputs line = read(facts);
        Map<UUID, Outcome> outcomes = new LinkedHashMap<>();
        for (UUID client : line.billedClients()) {
            UUID receiptId = receipts.register(facts, client);
            outcomes.put(client, generate(receiptId, line));
        }
        log.info("Recibos de la línea {} ciclo {}: {} clientes ({})", facts.creditAccountId(), facts.cycleNumber(),
                outcomes.size(), outcomes.values().stream().filter(o -> o == Outcome.GENERATED).count() + " generados");
        return outcomes;
    }

    /** Generates one receipt on its own (a retry): its line's inputs are read again. */
    public Outcome generate(UUID receiptId) {
        return generate(receiptId, null);
    }

    private Outcome generate(UUID receiptId, LineInputs preloaded) {
        var claimed = receipts.claim(receiptId, properties.getNodeId(), properties.getStaleAfter());
        if (claimed.isEmpty()) return Outcome.ALREADY_TAKEN;
        Claim c = claimed.get();
        long start = System.nanoTime();
        try {
            LineInputs line = preloaded != null ? preloaded : read(c.facts());
            ReceiptInputs inputs = line.inputsFor(c.beneficiaryPartyId(), BusinessClock.current().now());
            byte[] pdf = renderer.render(assembler.assemble(inputs, properties, zone));
            documents.store(receiptId, pdf, "application/pdf");
            receipts.markGenerated(receiptId, new ReceiptRepository.Generated(inputs.folio(), c.facts().obligorPartyId(),
                    inputs.client() != null ? inputs.client().name() : null, inputs.clientType(), inputs.live().size(),
                    inputs.totalDue(), inputs.paymentReference(), inputs.clabe(), json.writeValueAsString(inputs),
                    renderer.templateVersion(), pdf.length, properties.getNodeId()));
            receipts.findById(receiptId).ifPresent(publisher::publishGenerated);
            log.debug("Recibo {} generado línea={} ciclo={} cliente={} bytes={} en {} ms", inputs.folio(),
                    c.facts().creditAccountId(), c.facts().cycleNumber(), c.beneficiaryPartyId(), pdf.length,
                    (System.nanoTime() - start) / 1_000_000);
            return Outcome.GENERATED;
        } catch (Exception e) {
            String cause = e.getClass().getSimpleName() + ": " + e.getMessage();
            receipts.markFailed(receiptId, cause);
            log.warn("Recibo fallido línea={} ciclo={} cliente={} intento={}: {}", c.facts().creditAccountId(),
                    c.facts().cycleNumber(), c.beneficiaryPartyId(), c.attempts(), cause);
            return Outcome.FAILED;
        }
    }

    private LineInputs read(CutoffFacts f) {
        LineView view = source.view(f.creditAccountId(), f.effectivePeriodStart(), f.cutoffDate(), f.paymentDueDate());
        Set<UUID> clients = new HashSet<>();
        view.placementsOrEmpty().stream().map(PlacementLine::beneficiaryPartyId).filter(Objects::nonNull).forEach(clients::add);
        Set<UUID> people = new HashSet<>(clients);
        if (f.obligorPartyId() != null) people.add(f.obligorPartyId());
        return new LineInputs(f, view, source.references(f.creditAccountId()), source.parties(people),
                clients.isEmpty() ? Map.of() : source.profiles(clients));
    }

    /** A line's inputs, read once for all its clients. */
    record LineInputs(CutoffFacts facts, LineView view, Map<UUID, Long> references, Map<UUID, PartySummary> parties,
                      Map<UUID, ClientProfile> profiles) {

        /** The clients whose loans bill something in this statement, in a stable order. */
        List<UUID> billedClients() {
            return view.placementsOrEmpty().stream()
                    .filter(p -> p.beneficiaryPartyId() != null && p.live() && !p.dueOrEmpty().isEmpty())
                    .map(PlacementLine::beneficiaryPartyId)
                    .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        }

        ReceiptInputs inputsFor(UUID client, Instant issuedAt) {
            List<ReceiptInputs.Loan> live = new ArrayList<>();
            List<ReceiptInputs.Loan> settled = new ArrayList<>();
            view.placementsOrEmpty().stream()
                    .filter(p -> client.equals(p.beneficiaryPartyId()))
                    .sorted(Comparator.comparing(PlacementLine::placedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(p -> (p.live() ? live : settled).add(loanOf(p)));
            ClientProfile profile = profiles.get(client);
            PartySummary person = parties.get(client);
            PartySummary her = parties.get(facts.obligorPartyId());
            return new ReceiptInputs(facts, client,
                    new ReceiptInputs.Party(client, person != null ? person.displayName() : null, person != null ? person.rfc() : null),
                    new ReceiptInputs.Party(facts.obligorPartyId(), her != null ? her.displayName() : null, her != null ? her.rfc() : null),
                    view.account() != null ? view.account().clabe() : null, references.get(client),
                    profile != null ? profile.type() : null, profile != null ? profile.loansPaid() : 0,
                    profile == null ? List.of() : profile.flagsOrEmpty().stream().map(ReceiptSource.Flag::reason).distinct().toList(),
                    live, settled, issuedAt);
        }

        private static ReceiptInputs.Loan loanOf(PlacementLine p) {
            return new ReceiptInputs.Loan(p.dispositionId(), p.placedAt(), p.amount(), p.totalInstallments(),
                    p.outstandingPrincipal(), p.dueOrEmpty().stream()
                    .map(i -> new ReceiptInputs.Installment(i.number(), i.dueDate(), i.principal(), i.interest(), i.tax(),
                            i.total(), i.originalDueDate()))
                    .toList(), p.overdueInstallments(), p.oldestOverdueDue(), p.settledOn());
        }
    }
}
