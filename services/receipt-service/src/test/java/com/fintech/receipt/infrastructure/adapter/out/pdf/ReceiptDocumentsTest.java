package com.fintech.receipt.infrastructure.adapter.out.pdf;

import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.application.service.assembly.ReceiptAssembler;
import com.fintech.receipt.application.service.assembly.ReceiptInputs;
import com.fintech.receipt.support.ReceiptSamples;
import com.fintech.documents.DocumentModel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt rendered with the real template, read back from the PDF. The samples are written to
 * {@code build/recibos-muestra} to look at them.
 */
class ReceiptDocumentsTest {

    static final ZoneId CDMX = ZoneId.of("America/Mexico_City");
    static JasperReceiptRenderer renderer;
    final ReceiptAssembler assembler = new ReceiptAssembler();

    @BeforeAll
    static void compile() {
        System.setProperty("java.awt.headless", "true");
        renderer = new JasperReceiptRenderer(new ReceiptProperties());
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("el recibo dice lo que paga, a qué CLABE, con qué referencia, a quién le debe, y lleva la marca")
    void theReceiptReadsRight() throws Exception {
        ReceiptInputs in = ReceiptSamples.inputs(true);
        byte[] pdf = renderer.render(assembler.assemble(in, new ReceiptProperties(), CDMX));
        Path out = Path.of("build/recibos-muestra");
        Files.createDirectories(out);
        Files.write(out.resolve("recibo-pago-distribuidora.pdf"), pdf);

        String t = text(pdf).replaceAll("\\s+", " ");
        assertThat(t).contains("Recibo de pago").contains("Crédito con tu distribuidora")
                .contains("Rosa Elena Ibarra Salgado").contains("Cliente revolvente · 2 préstamos pagados")
                .contains("Total a pagar").contains("$1,337.45").contains("3 nov 2025")
                .contains("646180157000000004").contains("1234566").contains("Laura Méndez Ortiz")
                .contains("Tus préstamos con tu distribuidora").contains("9 de 12").contains("1 desde 18 oct 2025")
                .contains("Préstamos que terminaste de pagar en el periodo")
                .contains("Tienes una marca en tu historial de pagos")
                .contains("La obligación de tu deuda es con tu distribuidora")
                .contains("RC-20251021-1234566")
                .doesNotContain("descuento");
        assertThat(renderer.hasLogo()).isTrue();
        assertThat(renderer.templateVersion()).startsWith("recibo-pago-distribuidora@");
    }

    @Test
    @DisplayName("todos los parámetros de la plantilla los llena el ensamblador")
    void everyParameterIsFilled() {
        DocumentModel m = assembler.assemble(ReceiptSamples.inputs(true), new ReceiptProperties(), CDMX);
        assertThat(renderer.parameters()).allSatisfy(p -> assertThat(m.parameters()).as(p).containsKey(p));
    }

    @Test
    @DisplayName("rendimiento: 200 recibos ensamblados e impresos en segundos")
    void manyReceiptsAreCheap() throws Exception {
        ReceiptInputs in = ReceiptSamples.inputs(false);
        renderer.render(assembler.assemble(in, new ReceiptProperties(), CDMX));   // warm-up
        long start = System.nanoTime();
        int n = 200;
        long bytes = 0;
        for (int i = 0; i < n; i++) bytes += renderer.render(assembler.assemble(in, new ReceiptProperties(), CDMX)).length;
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("%d recibos en %d ms (%.1f ms cada uno, %d KB promedio)%n", n, ms, ms / (double) n, bytes / n / 1024);
        // The E2E prints ~2,700 receipts: at this pace a few seconds per replica, never the night's bottleneck.
        assertThat(ms).isLessThan(30_000);
    }
}
