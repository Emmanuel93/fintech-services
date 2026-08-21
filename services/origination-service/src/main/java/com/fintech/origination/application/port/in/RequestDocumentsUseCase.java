package com.fintech.origination.application.port.in;

import com.fintech.origination.application.RequestDocumentsCommand;

import java.util.UUID;

/** Ciclo de documentos pendientes de una solicitud en revisión (E6). */
public interface RequestDocumentsUseCase {

    /** Pide documentos: pasa la solicitud a PENDING_DOCUMENTS con un plazo y emite el evento. */
    void requestDocuments(RequestDocumentsCommand command);

    /** El sujeto entregó los documentos: la solicitud vuelve a la mesa de revisión. */
    void markDocumentsReceived(UUID applicationId);
}
