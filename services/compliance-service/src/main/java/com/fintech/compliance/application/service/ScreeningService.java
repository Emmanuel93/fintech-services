package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.WatchlistSearchPort;
import com.fintech.compliance.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Busca a una persona en las listas vigentes y emite un veredicto.
 *
 * <h2>Busca por tres caminos y se queda con la evidencia más fuerte de cada entrada</h2>
 *
 * Una misma entrada de lista puede aparecer por documento y por nombre a la vez. Reportarla dos
 * veces infla la cola y hace que el analista revise lo mismo dos veces; reportarla con la evidencia
 * más débil le quita fuerza a lo que encontró. Así que se deduplica **por entrada**, quedándose con
 * la razón más fuerte.
 *
 * <h2>El umbral de parecido es el parámetro que decide si el control sirve</h2>
 *
 * Demasiado bajo y toda la cartera cae en la cola por homonimia; demasiado alto y no cae nadie. Por
 * eso se configura y no se entierra, y por eso el veredicto guarda el parecido que justificó traer a
 * cada candidato: al calibrar hace falta ver qué se trajo con 0.82 y qué con 0.94.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final WatchlistSearchPort search;
    private final double minSimilarity;

    public ScreeningService(WatchlistSearchPort search,
                            @Value("${fintech.compliance.screening.min-similarity:0.85}") double minSimilarity) {
        this.search        = search;
        this.minSimilarity = minSimilarity;
    }

    public ScreeningOutcome screen(ScreeningSubject subject) {
        Set<UUID> versions = search.activeVersionIds();

        // Sin listas cargadas NO se puede decir «limpio»: sería afirmar que se comprobó algo que no
        // se comprobó. Es la misma polaridad que el control de `channels`, que fallaba abierto.
        if (versions.isEmpty()) {
            log.error("No hay ninguna lista vigente — el screening de partyId={} queda PENDIENTE, "
                      + "no limpio", subject.partyId());
            return new ScreeningOutcome(ScreeningStatus.PENDING, List.of(), Set.of());
        }

        NormalizedName name = subject.normalized();

        // Se acumula por entrada: la evidencia más fuerte gana.
        Map<UUID, MatchCandidate> porEntrada = new LinkedHashMap<>();

        if (subject.hasDocument()) {
            for (WatchlistEntry e : search.findByDocument(subject.curp(), subject.rfc(), versions)) {
                acumular(porEntrada, subject, e, MatchReason.DOCUMENT_IDENTICAL, 1.0);
            }
        }

        // La raíz del CURP, que es el camino para las entradas que NO traen documento.
        //
        // Va antes que el nombre a propósito: es evidencia más fuerte, y así la deduplicación por
        // entrada se queda con ella sin depender del orden en que lleguen las demás.
        var raices = subject.curpRoots();
        if (!raices.isEmpty()) {
            for (WatchlistEntry e : search.findByCurpRoot(raices, versions)) {
                acumular(porEntrada, subject, e, MatchReason.CURP_ROOT, 1.0);
            }
        }

        if (!name.isEmpty()) {
            for (WatchlistEntry e : search.findBySortedName(name.sorted(), versions)) {
                boolean mismaFecha = e.getDateOfBirth() != null
                        && e.getDateOfBirth().equals(subject.dateOfBirth());
                acumular(porEntrada, subject, e,
                        mismaFecha ? MatchReason.NAME_AND_DATE_OF_BIRTH : MatchReason.NAME_EXACT, 1.0);
            }

            for (var scored : search.findSimilarNames(name.value(), minSimilarity, versions)) {
                acumular(porEntrada, subject, scored.entry(), MatchReason.NAME_SIMILAR, scored.similarity());
            }
        }

        ScreeningOutcome outcome = ScreeningOutcome.of(new ArrayList<>(porEntrada.values()), versions);

        log.info("Screening partyId={} → {} · {}", subject.partyId(), outcome.status(), outcome.summary());
        return outcome;
    }

    /** Se queda con la razón más fuerte cuando la misma entrada aparece por varios caminos. */
    private void acumular(Map<UUID, MatchCandidate> acc, ScreeningSubject subject,
                          WatchlistEntry entry, MatchReason reason, double similarity) {

        MatchCandidate existente = acc.get(entry.getEntryId());
        if (existente != null && existente.reason().strength() >= reason.strength()) return;

        acc.put(entry.getEntryId(), new MatchCandidate(
                entry.getEntryId(), entry.getKind(), entry.getRawName(), entry.getProgram(),
                null, entry.getVersionId(), reason, similarity,
                MatchCandidate.discriminate(entry, subject.dateOfBirth(), subject.country(),
                        subject.curp(), subject.rfc())));
    }
}
