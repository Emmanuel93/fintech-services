package com.fintech.salesorg.domain;

/** Qué se asigna a una unidad. Generaliza para que los distribuidores reusen la misma tabla. */
public enum AssigneeType {
    /** Empleado interno (por su staffUserId). */
    STAFF,
    /** Distribuidor B2B2C (por su partyId). Se habilita en la fase de distribuidores. */
    DISTRIBUTOR
}
