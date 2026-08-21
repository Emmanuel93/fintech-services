package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.domain.OrgUnit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgUnitRepository {

    /** El IVA que aplica en una unidad, heredado del ancestro más cercano que lo declare. */
    java.math.BigDecimal findEffectiveVatRate(String code);

    OrgUnit save(OrgUnit unit);

    Optional<OrgUnit> findById(UUID unitId);

    Optional<OrgUnit> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByLevelId(UUID levelId);

    /** Todas las unidades (el árbol es chico: decenas–cientos, no millones). */
    List<OrgUnit> findAll();

    /** Nodos que referencian a una persona (staffUserId de un ejecutivo, partyId de un distribuidor). */
    List<OrgUnit> findByPartyRef(UUID partyRef);

    /** Hijos directos de una unidad. */
    List<OrgUnit> findChildren(UUID parentUnitId);

    /**
     * El subárbol completo bajo (e incluyendo) la unidad de {@code ancestorPath}: una sola consulta
     * LTREE indexada ({@code path <@ ancestorPath}). Es el alcance de un responsable de esa unidad.
     */
    List<OrgUnit> findSubtree(String ancestorPath);

    /** party_refs de los nodos vigentes de un nivel dado (por código) en el subárbol de un path. */
    List<UUID> findPartyRefsInSubtreeByLevel(String ancestorPath, String levelCode);

    /** party_ref del nodo vigente con un código dado en un nivel dado (resuelve un código a su party). */
    java.util.Optional<UUID> findPartyRefByCodeAndLevel(String code, String levelCode);
}
