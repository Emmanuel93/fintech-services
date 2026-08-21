package com.fintech.salesorg.domain;

import com.fintech.shared.exception.DomainException;

/** El cambio rompería la escalera de niveles o el árbol de unidades (padre/depth incoherentes). */
public class InvalidHierarchyException extends DomainException {
    public InvalidHierarchyException(String message) {
        super("INVALID_HIERARCHY", message);
    }
}
