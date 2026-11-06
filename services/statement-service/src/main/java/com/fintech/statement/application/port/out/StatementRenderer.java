package com.fintech.statement.application.port.out;

import com.fintech.documents.DocumentModel;
import com.fintech.statement.domain.StatementTemplate;

public interface StatementRenderer {

    byte[] render(StatementTemplate template, DocumentModel model);

    /** Qué versión de la plantilla se usó: queda grabada con el documento. */
    String templateVersion(StatementTemplate template);
}
