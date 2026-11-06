package com.fintech.statement.infrastructure.adapter.out.pdf;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.service.assembly.Assembled;
import com.fintech.statement.application.service.assembly.AssemblyContext;
import com.fintech.statement.application.service.assembly.DistributorStatementAssembler;
import com.fintech.statement.application.service.assembly.InstallmentStatementAssembler;
import com.fintech.statement.application.service.assembly.RevolvingStatementAssembler;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementTemplate;
import com.fintech.statement.support.Samples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brand on paper: the logo, Inter embedded, and one sample PDF per product left in
 * {@code build/estados-muestra} for whoever reviews the design. Also the proof that a palette other than
 * the design system's reaches the compiled template.
 */
class StatementGalleryTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final Path OUT = Path.of("build", "estados-muestra");
    private static JasperStatementRenderer renderer;

    @BeforeAll
    static void compilar() throws IOException {
        renderer = new JasperStatementRenderer(new StatementProperties());
        Files.createDirectories(OUT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(StatementTemplate.class)
    @DisplayName("cada producto sale con el logo y con Inter embebida; la muestra queda en build/estados-muestra")
    void brandOnEveryProduct(StatementTemplate plantilla) throws IOException {
        byte[] pdf = sample(renderer, plantilla);
        Files.write(OUT.resolve(plantilla.file() + ".pdf"), pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<String> fuentes = new HashSet<>();
            int imagenes = 0;
            for (PDPage page : doc.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    fuentes.add(font.getName());
                    assertThat(font.isEmbedded()).as("fuente embebida " + font.getName()).isTrue();
                }
                for (var name : page.getResources().getXObjectNames()) {
                    PDXObject x = page.getResources().getXObject(name);
                    if (x instanceof PDImageXObject) imagenes++;
                }
            }
            assertThat(fuentes).as(plantilla.file()).allMatch(f -> f.contains("Inter"));
            assertThat(imagenes).as("el logo en la primera página").isGreaterThanOrEqualTo(1);
            String texto = new PDFTextStripper().getText(doc);
            assertThat(texto).contains("Estado de cuenta").doesNotContain("Kredius\nEstado");
        }
    }

    @Test
    @DisplayName("sin logo se imprime el nombre del emisor; con otra paleta, la plantilla compila con esos colores")
    void configurableBranding() throws IOException {
        StatementProperties otra = new StatementProperties();
        otra.getBranding().setLogo("classpath:branding/no-existe.png");
        otra.getBranding().getPalette().setPrimary("#123456");
        otra.getBranding().getPalette().setAccent("0d47a1");   // another role's default, lower case, no '#'
        JasperStatementRenderer sinLogo = new JasperStatementRenderer(otra);

        assertThat(sinLogo.hasLogo()).isFalse();
        assertThat(sinLogo.templateVersion(StatementTemplate.PERSONAL_LOAN))
                .as("otra paleta, otra versión de plantilla")
                .isNotEqualTo(renderer.templateVersion(StatementTemplate.PERSONAL_LOAN));
        try (PDDocument doc = Loader.loadPDF(sample(sinLogo, StatementTemplate.PERSONAL_LOAN))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Kredius");
        }

        // Swapping a role for another role's default does not cascade; untouched roles stay as written.
        String jrxml = "<a forecolor=\"#0D47A1\" backcolor=\"#00B894\" c=\"#0F1A2E\"/>";
        assertThat(JasperStatementRenderer.applyPalette(jrxml, otra.getBranding().getPalette()))
                .isEqualTo("<a forecolor=\"#123456\" backcolor=\"#0D47A1\" c=\"#0F1A2E\"/>");
    }

    static byte[] sample(JasperStatementRenderer renderer, StatementTemplate plantilla) {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        List<UUID> clientes = List.of(UUID.randomUUID(), UUID.randomUUID());
        SourceView view = switch (plantilla.layout()) {
            case INSTALLMENT -> Samples.plazo(cuenta, titular);
            case REVOLVING -> Samples.revolvente(cuenta, titular);
            // The distributor's sample is the product as it works today: her early-payment discount netted, the payment
            // table at the top of the statement.
            case DISTRIBUTOR -> Samples.distribuidoraNeteada(cuenta, titular, clientes);
        };
        boolean distribuidora = plantilla.layout() == StatementLayout.DISTRIBUTOR;
        CutoffFacts f = Samples.facts(cuenta, titular, plantilla.name(), distribuidora ? "REVOLVING" : "INSTALLMENT",
                distribuidora ? "DISTRIBUTOR" : "CUSTOMER", 3, "16666.66", "2142.27",
                plantilla.layout() == StatementLayout.REVOLVING ? "475.00" : null);
        Map<UUID, PartySummary> personas = new java.util.HashMap<>();
        personas.put(titular, Samples.persona(titular, distribuidora ? "Beatriz Treviño Peralta" : "Irene Ibarra Cárdenas"));
        personas.put(clientes.get(0), Samples.persona(clientes.get(0), "Estela Islas Huerta"));
        personas.put(clientes.get(1), Samples.persona(clientes.get(1), "Urbano Solís Delgado"));
        AssemblyContext ctx = new AssemblyContext(UUID.randomUUID(), f, view, personas.get(titular), personas,
                Optional.of(new BigDecimal("18808.93")), new StatementProperties(), MX,
                Instant.parse("2026-12-01T06:05:00Z"),
                distribuidora ? new DistributorCommission(new BigDecimal("0.18"), List.of()) : DistributorCommission.NONE);
        Assembled doc = switch (plantilla.layout()) {
            case INSTALLMENT -> new InstallmentStatementAssembler().assemble(ctx);
            case REVOLVING -> new RevolvingStatementAssembler().assemble(ctx);
            case DISTRIBUTOR -> new DistributorStatementAssembler().assemble(ctx);
        };
        return renderer.render(plantilla, doc.model());
    }
}
