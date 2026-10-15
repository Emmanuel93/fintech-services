package com.fintech.compliance.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * El resultado de buscar a una persona en las listas.
 *
 * @param status      el veredicto. Sólo {@code CLEARED} deja pasar
 * @param candidates  quién se le parece y por qué, lo más fuerte primero
 * @param listVersions contra qué versiones de lista se corrió. <b>Sin esto el veredicto no se puede
 *                     auditar ni re-correr</b>: las listas cambian, y alguien despejado hace seis
 *                     meses puede estar hoy
 */
public record ScreeningOutcome(ScreeningStatus status,
                               List<MatchCandidate> candidates,
                               Set<UUID> listVersions) {

    public ScreeningOutcome {
        candidates   = candidates   == null ? List.of() : List.copyOf(candidates);
        listVersions = listVersions == null ? Set.of()  : Set.copyOf(listVersions);
    }

    /**
     * Decide el veredicto a partir de los candidatos.
     *
     * <p><b>Sólo bloquean los candidatos de listas que bloquean.</b> Una coincidencia con una lista
     * que no detiene —hoy la fiscal, pendiente de decisión— se registra y se muestra, pero no cambia
     * el veredicto: registrarla sin bloquear es informar; bloquear con ella sería aplicar un
     * criterio que nadie aprobó.
     */
    public static ScreeningOutcome of(List<MatchCandidate> candidates, Set<UUID> listVersions) {
        List<MatchCandidate> ordenados = candidates.stream()
                .sorted(Comparator
                        .comparingInt((MatchCandidate c) -> c.reason().strength()).reversed()
                        .thenComparing(c -> c.discriminators().size()))
                .toList();

        boolean alguienBloquea = ordenados.stream().anyMatch(MatchCandidate::blocks);

        return new ScreeningOutcome(
                alguienBloquea ? ScreeningStatus.POTENTIAL_MATCH : ScreeningStatus.CLEARED,
                ordenados, listVersions);
    }

    /** Los que hay que mirar primero: nada los descarta. */
    public List<MatchCandidate> unexplained() {
        return candidates.stream().filter(MatchCandidate::blocks)
                .filter(MatchCandidate::nothingRulesItOut).toList();
    }

    /** Para la cola del analista y para el log: por qué está donde está. */
    public String summary() {
        if (candidates.isEmpty()) return "sin coincidencias";
        MatchCandidate top = candidates.get(0);
        return "%d candidato(s); el más fuerte: %s por %s%s".formatted(
                candidates.size(), top.listedName(), top.reason(),
                top.nothingRulesItOut() ? " y NADA lo descarta"
                                        : " — descartado por: " + String.join(", ", top.discriminators()));
    }
}
