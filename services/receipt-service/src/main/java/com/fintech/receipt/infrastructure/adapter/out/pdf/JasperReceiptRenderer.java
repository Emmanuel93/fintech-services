package com.fintech.receipt.infrastructure.adapter.out.pdf;

import com.fintech.documents.DocumentModel;
import com.fintech.documents.pdf.JasperDocumentEngine;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.port.out.ReceiptRenderer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** The receipt's template on the shared documents engine: compiled once at start-up, in the configured brand. */
@Component
class JasperReceiptRenderer implements ReceiptRenderer {

    static final String TEMPLATE = "RECEIPT";
    static final String FILE = "recibo-pago-distribuidora";

    private final JasperDocumentEngine engine;

    JasperReceiptRenderer(ReceiptProperties properties) {
        this.engine = new JasperDocumentEngine(Map.of(TEMPLATE, FILE), properties.getBranding().getPalette(),
                properties.getBranding().getLogo());
    }

    @Override
    public byte[] render(DocumentModel model) {
        return engine.render(TEMPLATE, model);
    }

    @Override
    public String templateVersion() {
        return engine.version(TEMPLATE);
    }

    List<String> parameters() {
        return engine.parametersOf(TEMPLATE);
    }

    boolean hasLogo() {
        return engine.hasLogo();
    }
}
