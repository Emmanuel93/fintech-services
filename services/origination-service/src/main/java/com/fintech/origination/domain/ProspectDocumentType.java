package com.fintech.origination.domain;

public enum ProspectDocumentType {
    INE_FRONT,
    INE_BACK,
    ADDRESS_PROOF,
    INCOME_PROOF,

    /**
     * Prueba de vida. La app la captura desde el alta —es uno de los cuatro documentos
     * obligatorios— y no tenía dónde llegar: el expediente la rechazaba por no existir este valor.
     *
     * <p>Vive sólo en {@code prospect_document_files}: la tabla de declaraciones
     * ({@code prospect_documents}) tiene un CHECK con los cuatro tipos originales, y la selfie no
     * es un documento que el solicitante "entregue" en el sentido regulatorio — es evidencia de
     * la captura.
     */
    SELFIE
}
