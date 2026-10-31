package com.fintech.scoring.domain;

import java.util.List;

/**
 * Qué hace la política de un producto con quien no tiene historial en buró ({@code NO_HIT} o
 * {@code NOT_FOUND}).
 *
 * <p>Sin historial no hay reglas que evaluar, así que se le asigna un score —{@code 0} por defecto— y
 * ese score pasa por las <b>mismas bandas</b> de la política que cualquier otro: lo que decide es el
 * producto, no una regla global. Un préstamo personal rechaza; una línea de distribuidora manda a
 * revisión; un producto para estudiantes puede subir el score a la banda de revisión y pedir una
 * constancia de estudios.
 *
 * @param score        el score que recibe quien no tiene historial
 * @param requirements lo que se le pide además, cuando la decisión es revisión manual
 */
public record NoHistoryPolicy(int score, List<NoHistoryRequirement> requirements) {

    /** Score cero y nada más que pedir: lo que tenían todas las políticas antes de configurarlo. */
    public static final NoHistoryPolicy DEFAULT = new NoHistoryPolicy(0, List.of());

    public NoHistoryPolicy {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
