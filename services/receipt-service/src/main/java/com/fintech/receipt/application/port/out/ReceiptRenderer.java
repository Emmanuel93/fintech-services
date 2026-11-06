package com.fintech.receipt.application.port.out;

import com.fintech.documents.DocumentModel;

/** Fills the receipt's template into a PDF. */
public interface ReceiptRenderer {

    byte[] render(DocumentModel model);

    String templateVersion();
}
