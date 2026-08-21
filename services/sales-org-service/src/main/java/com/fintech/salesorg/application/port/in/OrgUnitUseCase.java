package com.fintech.salesorg.application.port.in;

import com.fintech.salesorg.domain.OrgUnit;

import java.util.List;
import java.util.UUID;

/** Administración y consulta del árbol de unidades comerciales. */
public interface OrgUnitUseCase {

    /** Da de alta una unidad, calculando su path a partir del padre y validando la jerarquía. */
    OrgUnit create(CreateOrgUnitCommand command);

    /** Todas las unidades (el árbol es chico). */
    List<OrgUnit> list();

    OrgUnit get(UUID unitId);

    /** Hijos directos de la unidad. */
    List<OrgUnit> children(UUID unitId);

    /** La unidad y todo su subárbol (su alcance). Una sola consulta LTREE. */
    List<OrgUnit> subtree(UUID unitId);

    /** partyIds de los distribuidores en el subárbol de la unidad (para acotar la cartera). */
    List<UUID> distributorsInSubtree(UUID unitId);

    /** staffUserIds de los ejecutivos en el subárbol de la unidad. */
    List<UUID> executivesInSubtree(UUID unitId);

    /** Resuelve el código de un distribuidor a su partyId (promoterCode → distribuidor). */
    java.util.Optional<UUID> resolveDistributorByCode(String code);

    /**
     * Cuelga una unidad —y su rama— de otro padre. Conserva id, código y asignaciones.
     */
    OrgUnit move(UUID unitId, UUID nuevoPadreId);
}
