package com.fintech.closing.domain;

/** Qué hacer cuando una fecha de corte cae en día inhábil. */
public enum NonBusinessDayShift {
    /** Se corre al siguiente hábil. Lo habitual: no se le cobra al cliente un día que no operó. */
    NEXT,
    /** Se adelanta al hábil anterior. Se usa cuando el corte no puede cruzar el fin de mes. */
    PREV,
    /** Se queda donde cayó. Válido para el devengo, que no necesita que el banco abra. */
    NONE
}
