package com.fintech.statement.infrastructure.adapter.out.pdf;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.EarnedCommission;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.service.assembly.Assembled;
import com.fintech.statement.application.service.assembly.AssemblyContext;
import com.fintech.statement.application.service.assembly.DistributorStatementAssembler;
import com.fintech.statement.application.service.assembly.InstallmentStatementAssembler;
import com.fintech.statement.application.service.assembly.RevolvingStatementAssembler;
import com.fintech.statement.application.service.assembly.StatementAssembler;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.domain.StatementTemplate;
import com.fintech.statement.support.Samples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La plantilla de cada producto, llenada y exportada de verdad. Se lee el texto del PDF: lo que
 * importa es lo que el cliente ve, no el mapa de parámetros.
 */
class StatementDocumentsTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static JasperStatementRenderer renderer;
    private final StatementProperties properties = new StatementProperties();

    @BeforeAll
    static void compilar() {
        renderer = new JasperStatementRenderer(new StatementProperties());
    }

    @Test
    @DisplayName("plazo fijo: la cuota del periodo, el avance del plan y sin pago mínimo")
    void plazoFijo() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 3,
                "16666.66", null, null);
        String texto = render(StatementTemplate.PERSONAL_LOAN, f, Samples.plazo(cuenta, titular),
                Map.of(titular, Samples.persona(titular, "Laura Méndez Ortiz")), "plazo");

        assertThat(texto).contains("Estado de cuenta", "Préstamo personal", "Laura Méndez Ortiz", "CTR-202609-0101",
                "1 al 30 de noviembre de 2026", "Pago a realizar", "$2,142.27", "Pagos realizados", "2 de 12",
                "Número total de pagos", "Saldo insoluto", "Pago del periodo: capital",
                "Plan de pagos", "Pagada", "Pendiente", "Saldo al corte", "$16,666.66",
                "CLABE de cobro 646180157000000004", "Usa tu número de contrato");
        // What every SOFOM ENR statement carries (CONDUSEF DT-ENR 35-36; LGOAAC 87-J).
        assertThat(texto).contains("Fintech Services, S.A. de C.V., SOFOM, E.N.R.", "RFC FSE200101AB1",
                "día inhábil", "Movimientos del periodo", "Comisiones cobradas en el periodo",
                "Total de comisiones cobradas en el periodo", "Costos de tu crédito", "Sin IVA",
                "Tasa de interés ordinaria anual fija", "Tasa de interés moratoria anual", "Base de cálculo",
                "Unidad Especializada de Atención a Usuarios", "CONDUSEF", "90 días naturales",
                "Contratar créditos que excedan tu capacidad",
                "no requiere de autorización de la Secretaría de Hacienda", "no es un comprobante fiscal");
        assertThat(texto).doesNotContain("Pago mínimo");
    }

    @Test
    @DisplayName("revolvente: movimientos, pago mínimo, pago para no generar intereses y el devengo sumado")
    void revolvente() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, "REVOLVING_LINE", "REVOLVING", "CUSTOMER", 4,
                "11221.78", "11221.78", "561.09");
        String texto = render(StatementTemplate.REVOLVING_LINE, f, Samples.revolvente(cuenta, titular),
                Map.of(titular, Samples.persona(titular, "Andrés Salgado Ruiz")), "revolvente", new BigDecimal("8000"));

        assertThat(texto).contains("Línea de crédito revolvente", "Andrés Salgado Ruiz",
                "Pago para no generar intereses", "$11,221.78", "Pago mínimo", "$561.09",
                "Límite de crédito", "$30,000.00", "Saldo anterior", "$8,000.00",
                "Movimientos del periodo", "Disposición", "Pago recibido", "Comisión por administración",
                "Intereses ordinarios del periodo", "$320.50", "IVA de intereses del periodo", "$51.28");
        // How to pay: the person's collection CLABE says who pays, the loan's numeric reference which loan. The PDF
        // wraps lines wherever the width ends, so the text is compared with its line breaks as spaces.
        assertThat(texto.replace('\n', ' ')).contains("CLABE de cobro 646180157000000004",
                "Escribe " + Samples.REVOLVING_REFERENCE + " en «referencia numérica»",
                "sin ella se aplica a tu crédito más antiguo");
    }

    @Test
    @DisplayName("distribuidora: cuánto enterar en el periodo y por qué cliente")
    void distribuidora() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        List<UUID> clientes = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        Map<UUID, PartySummary> personas = new HashMap<>();
        personas.put(titular, Samples.persona(titular, "Rosa Elena Castillo"));
        String[] nombres = {"Mario Pérez Luna", "Ana Torres Gil", "Luis Vega Soto", "Carmen Ríos Paz"};
        for (int i = 0; i < 4; i++) personas.put(clientes.get(i), Samples.persona(clientes.get(i), nombres[i]));
        // el quinto no está en party: sale con un identificador corto

        CutoffFacts f = Samples.facts(cuenta, titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5,
                "16666.70", "16666.70", null);
        String texto = render(StatementTemplate.DISTRIBUTOR_LINE, f, Samples.distribuidora(cuenta, titular, clientes),
                personas, "distribuidora");

        // Nothing accrued at the cutoff in this sample (principal = balance): the period asks for the principal of
        // the five installments it bills, 5 x 833.33. Each client's row says what that client pays this fortnight,
        // principal, interest and IVA of its installment: 833.33 + 140.00 + 22.40.
        assertThat(texto).contains("Línea de distribuidora", "Distribuidora", "Rosa Elena Castillo",
                "Total a pagar en el periodo", "$4,166.65", "Colocaciones vigentes", "A pagar", "Saldo insoluto",
                "Mario Pérez Luna", "Carmen Ríos Paz", "Cliente " + clientes.get(4).toString().substring(0, 8).toUpperCase(),
                "3 de 6", "$995.73", "Clientes con saldo", "CAT", "el pago de tu cliente en esta quincena",
                "Tu descuento por pronto pago", "Cómo se forma tu total a pagar", "Pagos de tus clientes en esta quincena");
        // The payment billed, not the ones paid: the sample bills the third of six.
        assertThat(texto).doesNotContain("2 de 6");
    }

    @Test
    @DisplayName("distribuidora con descuento por pronto pago: arriba el total, su descuento y la tablita de lo que entrega por día")
    void distribuidoraConDescuentoPorProntoPago() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        List<UUID> clientes = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        Map<UUID, PartySummary> personas = new HashMap<>();
        personas.put(titular, Samples.persona(titular, "Rosa Elena Castillo"));
        CutoffFacts f = Samples.facts(cuenta, titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5,
                "21583.35", "2739.90", null);
        // She paid the previous statement on its due date: 18 % applied in that delivery, inside this period.
        List<EarnedCommission> aplicados = List.of(new EarnedCommission(LocalDate.of(2026, 11, 18), new BigDecimal("2739.90"),
                new BigDecimal("0.18"), new BigDecimal("493.20"), BigDecimal.ZERO, "COMPENSATED", "202611", "NETTED", 4,
                LocalDate.of(2026, 11, 18), 0));
        Assembled doc = new DistributorStatementAssembler().assemble(new AssemblyContext(UUID.randomUUID(), f,
                Samples.distribuidoraNeteada(cuenta, titular, clientes), personas.get(titular), personas, Optional.empty(),
                properties, MX, Instant.parse("2026-12-01T07:00:00Z"), new DistributorCommission(new BigDecimal("0.18"), aplicados)));
        byte[] pdf = renderer.render(StatementTemplate.DISTRIBUTOR_LINE, doc.model());
        Path out = Path.of("build", "estados-de-cuenta", "distribuidora-descuento-por-pronto-pago.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
        String texto;
        try (PDDocument d = Loader.loadPDF(pdf)) {
            texto = new PDFTextStripper().getText(d);
        }

        // The key figures first: the total (5 x 547.98), her discount by the due date (5 x 98.64) and what she delivers.
        assertThat(texto).contains("Total a pagar en el periodo", "$2,739.90", "Tu descuento por pronto pago (18 %)", "$493.20",
                "Entregas a más tardar el 3 dic 2026", "$2,246.70");
        // The table, row by row: the discount and what she delivers add up to the total; nothing from the fourth day.
        assertThat(texto).contains("Tu pago con descuento por pronto pago", "Si pagas", "Tu descuento", "Entregas",
                "A más tardar el 3 dic 2026 (fecha límite)", "El 4 dic 2026", "$383.60", "$2,356.30",
                "El 5 dic 2026", "$301.40", "$2,438.50", "El 6 dic 2026", "$191.80", "$2,548.10",
                "Del 7 dic 2026 en adelante", "Sin descuento", "Cuenta el día en que entra tu pago, domingos incluidos");
        // The table is at the top: before the placements, not repeated in how the total is formed.
        assertThat(texto.indexOf("Tu pago con descuento por pronto pago")).isLessThan(texto.indexOf("Colocaciones vigentes"));
        assertThat(texto).doesNotContain("Entregas si pagas");
        // Her discount in the body: the two numbers and what she already applied this period.
        assertThat(texto).contains("Tu descuento por pronto pago", "Aplicado en tu entrega del 18 nov 2026", "$493.20");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(StatementTemplate.class)
    @DisplayName("cada producto sale con su plantilla: su título, su recuadro y su tabla, y no los de otro")
    void cadaProductoConSuPlantilla(StatementTemplate plantilla) throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        CutoffFacts f = Samples.facts(cuenta, titular, plantilla.name(),
                plantilla.layout() == StatementLayout.INSTALLMENT ? "INSTALLMENT" : "REVOLVING",
                plantilla.layout() == StatementLayout.DISTRIBUTOR ? "DISTRIBUTOR" : "CUSTOMER", 2,
                "5000", "5000", plantilla.layout() == StatementLayout.REVOLVING ? "250" : null);

        String texto = render(plantilla, f, vistaDe(plantilla.layout(), cuenta, titular),
                Map.of(titular, Samples.persona(titular, "Titular de prueba")), plantilla.file());

        List<String> propios = PROPIOS.get(plantilla);
        assertThat(texto).as(plantilla.file()).contains("Estado de cuenta", "Titular de prueba", "Cómo pagar");
        assertThat(texto).as(plantilla.file()).contains(propios);
        // El título de otro producto no aparece: cada plantilla es de su producto.
        PROPIOS.forEach((otra, textos) -> {
            if (otra != plantilla) {
                assertThat(texto).as(plantilla.file() + " no dice «" + textos.get(0) + "»").doesNotContain(textos.get(0));
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(StatementTemplate.class)
    @DisplayName("el ensamblador de su familia llena todos los parámetros que la plantilla del producto declara")
    void ningunParametroSinLlenar(StatementTemplate plantilla) {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        Assembled doc = assemblerDe(plantilla.layout()).assemble(ctx(Samples.facts(cuenta, titular, plantilla.name(),
                "REVOLVING", "CUSTOMER", 1, "1", "1", "1"), vistaDe(plantilla.layout(), cuenta, titular), Map.of(),
                Optional.empty()));
        // Las ranuras vacías a propósito (SUM7 en plazo, INFO6 en revolvente) llevan la llave con nulo.
        assertThat(doc.model().parameters().keySet()).as(plantilla.file())
                .containsAll(renderer.parametersOf(plantilla));
    }

    /** Lo que sólo dice la plantilla de ese producto: su nombre, su recuadro, su tabla. */
    private static final Map<StatementTemplate, List<String>> PROPIOS = Map.of(
            StatementTemplate.PERSONAL_LOAN, List.of("Préstamo personal", "Tu préstamo personal", "Plan de pagos"),
            StatementTemplate.PAYROLL_LOAN, List.of("Préstamo de nómina", "Tu préstamo de nómina",
                    "Descuentos programados", "Se descuenta"),
            StatementTemplate.GROUP_LOAN, List.of("Préstamo grupal", "El grupo responde en conjunto",
                    "Calendario de pagos del grupo"),
            StatementTemplate.MICRO_LOAN, List.of("Microcrédito", "Tu microcrédito", "Plan de pagos"),
            StatementTemplate.SME_LOAN, List.of("Crédito PyME", "El crédito de tu empresa", "Plan de amortización"),
            StatementTemplate.CREDIT_CARD, List.of("Tarjeta de crédito", "Compras a meses y promociones a plazo",
                    "Realizar sólo el pago mínimo aumenta el tiempo de pago", "Pago mínimo",
                    "Pagando sólo el mínimo liquidas en", "Pago fijo para liquidar en 12 meses"),
            StatementTemplate.REVOLVING_LINE, List.of("Línea de crédito revolvente", "Tu línea de crédito",
                    "Compras y disposiciones a plazos"),
            StatementTemplate.BUSINESS_REVOLVING_LINE, List.of("Línea revolvente empresarial", "La línea de tu empresa",
                    "Compras y disposiciones a plazos"),
            StatementTemplate.DISTRIBUTOR_LINE, List.of("Línea de distribuidora", "Tu línea de distribuidora",
                    "Colocaciones vigentes", "Total a pagar en el periodo"));

    private static StatementAssembler assemblerDe(StatementLayout forma) {
        return switch (forma) {
            case INSTALLMENT -> new InstallmentStatementAssembler();
            case REVOLVING -> new RevolvingStatementAssembler();
            case DISTRIBUTOR -> new DistributorStatementAssembler();
        };
    }

    private static SourceView vistaDe(StatementLayout forma, UUID cuenta, UUID titular) {
        return switch (forma) {
            case INSTALLMENT -> Samples.plazo(cuenta, titular);
            case REVOLVING -> Samples.revolvente(cuenta, titular);
            case DISTRIBUTOR -> Samples.distribuidora(cuenta, titular, List.of(UUID.randomUUID()));
        };
    }

    @Test
    @DisplayName("una línea sin colocaciones o una cuenta sin movimientos sigue dando un documento")
    void sinFilas() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        SourceView v = Samples.distribuidora(cuenta, titular, List.of());
        String texto = render(StatementTemplate.DISTRIBUTOR_LINE,
                Samples.facts(cuenta, titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 1, "0", "0", null),
                v, Map.of(), "vacio");
        // The sample still accrues interest in the period, so it has movements; it charged no fees.
        assertThat(texto).contains("La línea no tiene colocaciones vigentes.", "Titular de la cuenta",
                "Intereses ordinarios del periodo", "No se cobraron comisiones en el periodo.");
    }

    @Test
    @DisplayName("rendimiento: un estado de cuenta se llena y exporta en milisegundos con la plantilla compilada")
    void rendimiento() {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        List<UUID> clientes = IntStream.range(0, 40).mapToObj(i -> UUID.randomUUID()).toList();
        var assembler = new DistributorStatementAssembler();
        var view = Samples.distribuidora(cuenta, titular, clientes);
        var f = Samples.facts(cuenta, titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5, "1", "1", null);
        for (int i = 0; i < 5; i++) renderer.render(StatementTemplate.DISTRIBUTOR_LINE, assembler.assemble(ctx(f, view, Map.of(), Optional.empty())).model());

        int n = 50;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            renderer.render(StatementTemplate.DISTRIBUTOR_LINE, assembler.assemble(ctx(f, view, Map.of(), Optional.empty())).model());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000 / n;
        System.out.printf("[rendimiento] distribuidora con 40 colocaciones: %d ms por PDF en un hilo%n", ms);
        assertThat(ms).as("ms por PDF").isLessThan(500L);
    }

    private String render(StatementTemplate plantilla, CutoffFacts f, SourceView v, Map<UUID, PartySummary> personas,
                          String nombre) throws Exception {
        return render(plantilla, f, v, personas, nombre, null);
    }

    private String render(StatementTemplate plantilla, CutoffFacts f, SourceView v, Map<UUID, PartySummary> personas,
                          String nombre, BigDecimal anterior) throws Exception {
        Assembled doc = assemblerDe(plantilla.layout()).assemble(ctx(f, v, personas, Optional.ofNullable(anterior)));
        byte[] pdf = renderer.render(plantilla, doc.model());
        Path out = Path.of("build", "estados-de-cuenta", nombre + ".pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    private AssemblyContext ctx(CutoffFacts f, SourceView v, Map<UUID, PartySummary> personas, Optional<BigDecimal> anterior) {
        return new AssemblyContext(UUID.randomUUID(), f, v, personas.get(f.obligorPartyId()), personas, anterior,
                properties, MX, Instant.parse("2026-12-01T07:00:00Z"));
    }
}
