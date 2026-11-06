package com.fintech.statement.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.shared.time.BusinessClock;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementDocumentStore;
import com.fintech.statement.application.port.out.StatementRenderer;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.service.assembly.Assembled;
import com.fintech.statement.application.service.assembly.AssemblyContext;
import com.fintech.statement.application.service.assembly.StatementAssembler;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.Statement;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementStatus;
import com.fintech.statement.domain.StatementTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vuelve a producir el PDF de un estado de cuenta ya emitido, con la plantilla de hoy y los datos de entonces.
 *
 * <p>Cada documento guarda la foto con la que se armó (el corte y la vista de cartera). Cuando cambia el
 * diseño —la marca, un texto, una columna— no hace falta volver a cortar ni a preguntarle a cartera: se
 * ensambla otra vez desde la foto. Sólo los nombres se piden de nuevo a party, que no están en la foto y
 * son datos de referencia.
 *
 * <p>Lo que no cambia: la fecha de emisión (el PDF sigue diciendo cuándo se emitió), el folio, las cifras.
 * No se publica {@code statement-generated}: el cliente ya fue notificado de ese estado; esto sólo cambia
 * cómo se ve. Un documento que no se pueda regenerar conserva el anterior y se cuenta como fallido.
 */
@Service
public class StatementRegenerationService {

    private static final Logger log = LoggerFactory.getLogger(StatementRegenerationService.class);
    private static final int PAGE = 200;

    private final StatementRepository statements;
    private final StatementSource source;
    private final StatementRenderer renderer;
    private final StatementDocumentStore documents;
    private final StatementProperties properties;
    private final ObjectMapper json;
    private final BusinessClock clock;
    private final Map<StatementLayout, StatementAssembler> assemblers = new EnumMap<>(StatementLayout.class);

    public StatementRegenerationService(StatementRepository statements, StatementSource source,
                                        StatementRenderer renderer, StatementDocumentStore documents,
                                        StatementProperties properties, ObjectMapper json, BusinessClock clock,
                                        List<StatementAssembler> available) {
        this.statements = statements;
        this.source     = source;
        this.renderer   = renderer;
        this.documents  = documents;
        this.properties = properties;
        this.json       = json;
        this.clock      = clock;
        available.forEach(a -> assemblers.put(a.layout(), a));
    }

    /** Lo que pasó con un documento. */
    public enum Outcome { REGENERATED, SKIPPED, FAILED }

    /** Cuántos se regeneraron, cuántos se saltaron (no generados o sin foto) y cuántos fallaron. */
    public record Result(int regenerated, int skipped, int failed) {
        public Result plus(Outcome o) {
            return switch (o) {
                case REGENERATED -> new Result(regenerated + 1, skipped, failed);
                case SKIPPED -> new Result(regenerated, skipped + 1, failed);
                case FAILED -> new Result(regenerated, skipped, failed + 1);
            };
        }
    }

    /** Todos los generados, o los de una cuenta, por páginas: nunca una lista entera en memoria. */
    public Result regenerateAll(UUID creditAccountId) {
        Result result = new Result(0, 0, 0);
        UUID after = null;
        long inicio = System.nanoTime();
        while (true) {
            List<UUID> page = statements.generated(creditAccountId, after, PAGE);
            if (page.isEmpty()) break;
            for (UUID id : page) {
                result = result.plus(regenerate(id));
            }
            after = page.get(page.size() - 1);
        }
        log.info("Regeneración de estados de cuenta cuenta={} regenerados={} saltados={} fallidos={} en {} ms",
                creditAccountId == null ? "todas" : creditAccountId, result.regenerated(), result.skipped(),
                result.failed(), (System.nanoTime() - inicio) / 1_000_000);
        return result;
    }

    public Outcome regenerate(UUID statementId) {
        Optional<Statement> found = statements.findById(statementId);
        if (found.isEmpty() || found.get().status() != StatementStatus.GENERATED) {
            return Outcome.SKIPPED;
        }
        Statement s = found.get();
        Optional<String> snapshot = statements.findSnapshot(statementId);
        if (snapshot.isEmpty()) {
            log.warn("Estado de cuenta {} sin foto: no se puede regenerar", statementId);
            return Outcome.SKIPPED;
        }
        try {
            JsonNode foto = json.readTree(snapshot.get());
            CutoffFacts f = json.treeToValue(foto.get("cutoff"), CutoffFacts.class);
            SourceView view = json.treeToValue(foto.get("view"), SourceView.class);
            StatementTemplate plantilla = StatementTemplate.forProduct(f.productType()).orElseThrow(() ->
                    new IllegalStateException("No hay plantilla de estado de cuenta para el producto " + f.productType()));
            Map<UUID, PartySummary> personas = source.parties(StatementGenerationService.partiesOf(f, view));
            // The issue instant the document printed: business time, kept in the snapshot. Older snapshots did
            // not keep it; the real generated_at is then the best there is (and exact when the clock is not shifted).
            Instant emitido = foto.hasNonNull("issuedAt") ? json.treeToValue(foto.get("issuedAt"), Instant.class)
                    : s.generatedAt() != null ? s.generatedAt() : BusinessClock.current().now();

            // The commission the statement printed; one kept before it was shown is asked for again (a payment's
            // commission is fixed once earned).
            DistributorCommission comision = foto.hasNonNull("commission")
                    ? json.treeToValue(foto.get("commission"), DistributorCommission.class)
                    : plantilla.layout() == StatementLayout.DISTRIBUTOR ? source.commission(f.creditAccountId())
                    : DistributorCommission.NONE;

            AssemblyContext ctx = new AssemblyContext(statementId, f, view, personas.get(f.obligorPartyId()),
                    personas, statements.previousBalance(f.creditAccountId(), f.cycleNumber()), properties,
                    clock.zone(), emitido, comision);
            Assembled doc = assemblers.get(plantilla.layout()).assemble(ctx);
            byte[] pdf = renderer.render(plantilla, doc.model());

            documents.store(statementId, pdf, "application/pdf");
            statements.markRegenerated(statementId, renderer.templateVersion(plantilla), pdf.length);
            return Outcome.REGENERATED;
        } catch (Exception e) {
            log.warn("No se pudo regenerar el estado de cuenta {} (cuenta={} ciclo={}): {}", statementId,
                    s.creditAccountId(), s.cycleNumber(), e.getMessage());
            return Outcome.FAILED;
        }
    }
}
