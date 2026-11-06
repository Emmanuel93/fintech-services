package com.fintech.statement.infrastructure.adapter.out.pdf;

import com.fintech.documents.Brand;
import com.fintech.documents.DocumentModel;
import com.fintech.documents.pdf.JasperDocumentEngine;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.StatementProperties.Palette;
import com.fintech.statement.application.port.out.StatementRenderer;
import com.fintech.statement.domain.StatementTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The statements' templates on the shared documents engine: compiled once at start-up in the configured brand's
 * colors, each with the version (name and hash) the statement stores.
 */
@Component
class JasperStatementRenderer implements StatementRenderer {

    static final String LOGO_PARAMETER = JasperDocumentEngine.LOGO_PARAMETER;

    private final JasperDocumentEngine engine;

    JasperStatementRenderer(StatementProperties properties) {
        Map<String, String> templates = new LinkedHashMap<>();
        for (StatementTemplate template : StatementTemplate.values()) {
            templates.put(template.name(), template.file());
        }
        this.engine = new JasperDocumentEngine(templates, properties.getBranding().getPalette().roles(),
                properties.getBranding().getLogo());
    }

    @Override
    public byte[] render(StatementTemplate template, DocumentModel model) {
        return engine.render(template.name(), model);
    }

    @Override
    public String templateVersion(StatementTemplate template) {
        return engine.version(template.name());
    }

    List<String> parametersOf(StatementTemplate template) {
        return engine.parametersOf(template.name());
    }

    boolean hasLogo() {
        return engine.hasLogo();
    }

    static String applyPalette(String jrxml, Palette palette) {
        return Brand.applyPalette(jrxml, Palette.defaults().roles(), palette.roles());
    }
}
