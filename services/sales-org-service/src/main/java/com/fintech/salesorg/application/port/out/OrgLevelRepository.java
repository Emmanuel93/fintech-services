package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.domain.OrgLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgLevelRepository {

    OrgLevel save(OrgLevel level);

    Optional<OrgLevel> findById(UUID levelId);

    Optional<OrgLevel> findByCode(String code);

    Optional<OrgLevel> findByDepth(int depth);

    boolean existsByCode(String code);

    /** La escalera completa, de la raíz (depth 0) hacia abajo. */
    List<OrgLevel> findAllOrderByDepthAsc();

    /** Niveles en profundidad &gt;= depth, del más profundo al menos (para correr al insertar). */
    List<OrgLevel> findFromDepthDescending(int depth);

    /** La profundidad máxima ocupada, para saber dónde anexar el siguiente nivel. */
    Optional<Integer> maxDepth();
}
