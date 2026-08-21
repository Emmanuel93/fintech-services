package com.fintech.accounting.domain;

/**
 * Una póliza no se borra ni se edita.
 *
 * <p>{@code CANCELLED} significa que existe otra póliza de reversa que la deja en cero, y las dos
 * siguen visibles. Borrarla dejaría un hueco en el folio consecutivo de la sucursal, que es lo
 * primero que busca quien audita.
 */
public enum VoucherStatus {
    POSTED, CANCELLED
}
