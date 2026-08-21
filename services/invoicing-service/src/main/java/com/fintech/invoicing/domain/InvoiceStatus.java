package com.fintech.invoicing.domain;

public enum InvoiceStatus {
    DRAFT,      // generada, aún sin timbrar
    STAMPED,    // timbrada por el PAC (folio fiscal asignado)
    CANCELLED
}
