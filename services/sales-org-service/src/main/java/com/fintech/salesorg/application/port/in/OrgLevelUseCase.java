package com.fintech.salesorg.application.port.in;

import com.fintech.salesorg.domain.OrgLevel;

import java.util.List;

/**
 * Administración de la escalera de niveles. Es la parte "configurable" de la matriz de escalado:
 * se anexan niveles al final o se insertan intermedios, sin tocar código.
 */
public interface OrgLevelUseCase {

    /** Anexa un nivel al fondo de la escalera (profundidad = máxima actual + 1, o 0 si está vacía). */
    OrgLevel append(String code, String name);

    /**
     * Inserta un nivel en una posición intermedia, corriendo hacia abajo los niveles iguales o más
     * profundos. Solo se permite si aún no hay unidades en esas posiciones (si las hay, el cambio
     * exige reasignar el árbol — trabajo del job de reasignación).
     */
    OrgLevel insertAt(int depth, String code, String name);

    /** La escalera completa, de la raíz hacia abajo. */
    List<OrgLevel> list();
}
