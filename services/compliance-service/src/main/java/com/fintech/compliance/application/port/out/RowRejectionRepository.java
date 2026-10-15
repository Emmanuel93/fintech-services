package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.RowRejection;

import java.util.List;
import java.util.UUID;

/**
 * Las filas que no se pudieron usar.
 *
 * <p>Se guardan y no sólo se cuentan: «se rechazaron 812 filas» no permite arreglar nada, «la línea
 * 4 417 no traía nombre» sí. Es la diferencia entre saber que el layout cambió y saber qué cambió.
 */
public interface RowRejectionRepository {

    void saveAll(List<RowRejection> batch);

    List<RowRejection> findByVersionId(UUID versionId, int limit);
}
