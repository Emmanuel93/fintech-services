package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.WatchlistEntry;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Buscar a alguien en las versiones vigentes de las listas.
 *
 * <p>Las tres búsquedas están separadas y no unidas en una sola consulta «inteligente» porque
 * <b>cada una es una evidencia distinta</b>, y el resultado tiene que poder decir cuál fue. Una
 * consulta que devolviera «coincide» sin decir por qué obligaría al analista a reconstruirlo.
 *
 * <p>Todas reciben las versiones vigentes en vez de resolverlas dentro: así el veredicto queda atado
 * a un conjunto concreto de versiones, y una activación a mitad del screening no mezcla resultados
 * de dos listas distintas.
 */
public interface WatchlistSearchPort {

    /** Las versiones vigentes hoy, una por lista. Es contra éstas que se decide. */
    Set<UUID> activeVersionIds();

    /** Coincidencia por documento: es una identificación, no un parecido. */
    List<WatchlistEntry> findByDocument(String curp, String rfc, Set<UUID> versions);

    /**
     * Entradas cuya <b>raíz de CURP derivada</b> coincide con alguna de las de la persona.
     *
     * <p>Es el camino que existe para las entradas que <b>no traen CURP</b>, que son la mayoría. No
     * sustituye al cruce por documento: lo complementa donde aquél no puede encontrar nada.
     *
     * @param roots las raíces de la persona — la de su CURP real y las derivadas de su nombre y
     *              fecha; vacío significa que no hay nada que buscar, no que no haya coincidencias
     */
    List<WatchlistEntry> findByCurpRoot(Set<String> roots, Set<UUID> versions);

    /** Los mismos tokens del nombre, en cualquier orden. */
    List<WatchlistEntry> findBySortedName(String sortedName, Set<UUID> versions);

    /**
     * Nombres parecidos por encima del umbral.
     *
     * @param minSimilarity 0..1. Es el parámetro que decide si el control bloquea a media cartera o
     *                      no bloquea a nadie, así que se pasa explícito y se configura, no se
     *                      entierra en la consulta
     */
    List<ScoredEntry> findSimilarNames(String normalizedName, double minSimilarity, Set<UUID> versions);

    /** Una entrada con su parecido, para no perder el número que justificó traerla. */
    record ScoredEntry(WatchlistEntry entry, double similarity) {}
}
