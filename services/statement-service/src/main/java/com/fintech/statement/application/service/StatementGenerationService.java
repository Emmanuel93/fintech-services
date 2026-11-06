package com.fintech.statement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementDocumentStore;
import com.fintech.statement.application.port.out.StatementEventPublisher;
import com.fintech.statement.application.port.out.StatementRenderer;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.port.out.StatementRepository.Claim;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.service.assembly.Assembled;
import com.fintech.statement.application.service.assembly.AssemblyContext;
import com.fintech.statement.application.service.assembly.StatementAssembler;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementTemplate;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Del corte sellado al PDF.
 *
 * <pre>
 *   cutoff-closed ─► registrar (cuenta, ciclo) ─► reclamar ─► vista de cartera + nombres
 *                ─► ensamblador de su forma ─► plantilla de su producto ─► guardar PDF ─► GENERATED
 *                ─► statement.statement-generated
 * </pre>
 *
 * <h2>Idempotente y sin coordinador</h2>
 *
 * El registro usa la llave natural (cuenta, ciclo): el mismo corte entregado dos veces encuentra el
 * mismo documento. El reclamo es un {@code UPDATE … RETURNING} atómico: entre dos pods —o dos
 * entregas del mismo mensaje— sólo uno lo genera. No hay transacción abierta mientras se consulta a
 * cartera ni mientras Jasper llena el documento: cada paso es corto.
 *
 * <h2>Un fallo no se pierde</h2>
 *
 * Si cartera no responde o la plantilla truena, el documento queda {@code FAILED} con la causa y el
 * reintento lo retoma con los mismos datos del corte, que quedaron guardados al registrarlo.
 */
@Service
public class StatementGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StatementGenerationService.class);

    private final StatementRepository statements;
    private final StatementSource source;
    private final StatementRenderer renderer;
    private final StatementDocumentStore documents;
    private final StatementEventPublisher publisher;
    private final StatementProperties properties;
    private final ObjectMapper json;
    private final BusinessClock clock;
    private final Map<StatementLayout, StatementAssembler> assemblers = new EnumMap<>(StatementLayout.class);

    public StatementGenerationService(StatementRepository statements, StatementSource source,
                                      StatementRenderer renderer, StatementDocumentStore documents,
                                      StatementEventPublisher publisher, StatementProperties properties,
                                      ObjectMapper json, BusinessClock clock, List<StatementAssembler> available) {
        this.statements = statements;
        this.source     = source;
        this.renderer   = renderer;
        this.documents  = documents;
        this.publisher  = publisher;
        this.properties = properties;
        this.json       = json;
        this.clock      = clock;
        available.forEach(a -> assemblers.put(a.layout(), a));
        for (StatementLayout layout : StatementLayout.values()) {
            if (!assemblers.containsKey(layout)) {
                throw new IllegalStateException("Falta el ensamblador de la forma " + layout);
            }
        }
    }

    /** Lo que pasó con un corte. */
    public enum Outcome { GENERATED, ALREADY_TAKEN, FAILED }

    /** Un corte recién sellado: registra su documento y lo genera si nadie más lo tiene. */
    public Outcome onCutoffClosed(CutoffFacts facts) {
        StatementLayout layout = StatementLayout.of(facts.productType(), facts.effectiveHolderKind(),
                facts.effectiveBehavior());
        UUID statementId = statements.register(facts, layout);
        return generate(statementId);
    }

    /** Genera un documento registrado. Lo usan el consumidor y el reintento. */
    public Outcome generate(UUID statementId) {
        Optional<Claim> claim = statements.claim(statementId, properties.getNodeId(), properties.getStaleAfter());
        if (claim.isEmpty()) {
            return Outcome.ALREADY_TAKEN;
        }
        Claim c = claim.get();
        CutoffFacts f = c.facts();
        long inicio = System.nanoTime();
        try {
            // Primero la plantilla: sin ella no tiene caso pedirle nada a cartera.
            StatementTemplate plantilla = StatementTemplate.forProduct(f.productType()).orElseThrow(() ->
                    new IllegalStateException("No hay plantilla de estado de cuenta para el producto " + f.productType()));
            SourceView view = source.view(f.creditAccountId(), f.effectivePeriodStart(), f.cutoffDate(),
                    f.paymentDueDate());
            Map<UUID, PartySummary> personas = source.parties(partiesOf(f, view));
            // A distributor's statement says what she earns on what her clients pay.
            DistributorCommission comision = plantilla.layout() == StatementLayout.DISTRIBUTOR
                    ? source.commission(f.creditAccountId()) : DistributorCommission.NONE;

            // The issue instant is business time (the night after the cutoff), which is what the document prints.
            Instant emitido = BusinessClock.current().now();
            AssemblyContext ctx = new AssemblyContext(statementId, f, view, personas.get(f.obligorPartyId()),
                    personas, statements.previousBalance(f.creditAccountId(), f.cycleNumber()), properties,
                    clock.zone(), emitido, comision);
            Assembled doc = assemblers.get(plantilla.layout()).assemble(ctx);
            byte[] pdf = renderer.render(plantilla, doc.model());

            documents.store(statementId, pdf, "application/pdf");
            statements.markGenerated(statementId, new StatementRepository.Generated(
                    view.account().contractNumber(), doc.amountDue(), doc.minimumPayment(),
                    snapshot(f, view, comision, emitido), renderer.templateVersion(plantilla), pdf.length,
                    properties.getNodeId()));
            statements.findById(statementId).ifPresent(publisher::publishGenerated);

            log.info("Estado de cuenta generado cuenta={} ciclo={} plantilla={} bytes={} en {} ms",
                    f.creditAccountId(), f.cycleNumber(), plantilla.file(), pdf.length,
                    (System.nanoTime() - inicio) / 1_000_000);
            return Outcome.GENERATED;
        } catch (RuntimeException e) {
            String causa = e.getClass().getSimpleName() + ": " + e.getMessage();
            statements.markFailed(statementId, causa);
            log.warn("Estado de cuenta fallido cuenta={} ciclo={} intento={}: {}",
                    f.creditAccountId(), f.cycleNumber(), c.attempts(), causa);
            return Outcome.FAILED;
        }
    }

    /** El titular y, en una línea de distribuidora, cada cliente: una sola llamada a party. */
    static Set<UUID> partiesOf(CutoffFacts f, SourceView view) {
        Set<UUID> ids = new HashSet<>();
        if (f.obligorPartyId() != null) ids.add(f.obligorPartyId());
        if (view.placements() != null) {
            view.placements().stream().map(StatementSource.PlacementLine::beneficiaryPartyId)
                    .filter(Objects::nonNull).forEach(ids::add);
        }
        return ids;
    }

    /**
     * La foto con la que se armó el documento: si alguien pregunta por qué dice lo que dice, y con la que se
     * vuelve a producir si cambia la plantilla. Lleva la fecha de emisión en tiempo de negocio, que no es la
     * {@code generated_at} de la base (tiempo real) cuando el reloj de negocio está desplazado.
     */
    private String snapshot(CutoffFacts f, SourceView view, DistributorCommission commission, Instant issuedAt) {
        try {
            Map<String, Object> foto = new LinkedHashMap<>();
            foto.put("cutoff", f);
            foto.put("view", view);
            if (commission != DistributorCommission.NONE) {
                foto.put("commission", commission);
            }
            foto.put("issuedAt", issuedAt);
            return json.writeValueAsString(foto);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar la foto del estado de cuenta", e);
        }
    }
}
