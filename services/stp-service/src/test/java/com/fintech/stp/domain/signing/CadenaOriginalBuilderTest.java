package com.fintech.stp.domain.signing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vectores de oro del sello STP.
 *
 * <p>Estos tests son el contrato con Banxico, no una comprobación de estilo. La cadena esperada
 * está copiada literalmente del test del servicio legado ({@code StringServiceTest}), que llevaba
 * años firmando órdenes reales. Si alguno falla, STP rechazará todo.
 *
 * <p>SG-T01 vector de oro de OrdenPagoFirma · SG-T02 nulos · SG-T03 ConciliacionFirma ·
 * SG-T04 independencia del Locale · SG-T05 formato de importe · SG-T06 nulo en primera posición.
 */
class CadenaOriginalBuilderTest {

    private static final String GOLDEN_ORDEN_PAGO =
            "||846|empresa|20230516|folioOrigen|claveRastreo|90646|100.00|1|"
            + "3|nombreOrdenante|cuentaOrdenante|rfcCurpOrdenante|3|"
            + "nombreBeneficiario|cuentaBeneficiario|rfcCurpBeneficiario|emailBeneficiario|3|"
            + "nombreBeneficiario2|cuentaBeneficiario2|rfcCurpBeneficiario2|conceptoPago|conceptoPago2|"
            + "claveCatalogoUsuario|claveCatalogoUsuario2|clavePago|referenciaCobranza|12345|"
            + "tipoOperacion|topologia|usuario|medioEntrega|prioridad|0.16||";

    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    @DisplayName("SG-T01 · la cadena de OrdenPagoFirma coincide byte a byte con el vector del legado")
    void goldenVectorOrdenPago() {
        assertThat(CadenaOriginalBuilder.build(goldenOrder())).isEqualTo(GOLDEN_ORDEN_PAGO);
    }

    @Test
    @DisplayName("SG-T02 · un componente nulo se rinde como cadena vacía")
    void nullsRenderAsEmptyString() {
        assertThat(CadenaOriginalBuilder.build(new SaldoCuentaFirma("empresa", "cuenta_prueba", null)))
                .isEqualTo("||empresa|cuenta_prueba|||");
    }

    @Test
    @DisplayName("SG-T03 · ConciliacionFirma — camino crítico del poller de liquidación")
    void conciliacionFirma() {
        assertThat(CadenaOriginalBuilder.build(
                new ConciliacionFirma("EMPRESA", "E", LocalDate.of(2025, 6, 11))))
                .isEqualTo("||EMPRESA|E|20250611||");
    }

    @Test
    @DisplayName("SG-T04 · la cadena no cambia con un Locale de coma decimal")
    void independentOfDefaultLocale() {
        Locale.setDefault(Locale.GERMANY);
        assertThat(CadenaOriginalBuilder.build(goldenOrder())).isEqualTo(GOLDEN_ORDEN_PAGO);

        Locale.setDefault(Locale.of("es", "MX"));
        assertThat(CadenaOriginalBuilder.build(goldenOrder())).isEqualTo(GOLDEN_ORDEN_PAGO);
    }

    @Test
    @DisplayName("SG-T05 · importes con dos decimales y redondeo HALF_EVEN")
    void amountFormat() {
        assertThat(amountField(BigDecimal.valueOf(100))).isEqualTo("100.00");
        assertThat(amountField(new BigDecimal("100.5"))).isEqualTo("100.50");
        assertThat(amountField(new BigDecimal("0.162"))).isEqualTo("0.16");
        assertThat(amountField(new BigDecimal("0.165"))).isEqualTo("0.16");   // HALF_EVEN baja
        assertThat(amountField(new BigDecimal("0.175"))).isEqualTo("0.18");   // HALF_EVEN sube
        assertThat(amountField(new BigDecimal("1234567.891"))).isEqualTo("1234567.89");
    }

    @Test
    @DisplayName("SG-T06 · un nulo en la primera posición produce pipes consecutivos")
    void nullInFirstPosition() {
        OrdenPagoFirma sinContraparte = OrdenPagoFirma.builder().empresa("empresa").build();
        assertThat(CadenaOriginalBuilder.build(sinContraparte)).startsWith("|||empresa|");
    }

    @Test
    @DisplayName("El nombre del beneficiario se trunca a 40 al construir, no al persistir")
    void beneficiaryNameTruncatedAtBuild() {
        String largo = "MARIA DE LOS ANGELES HERNANDEZ RODRIGUEZ DE LA TORRE";
        assertThat(OrdenPagoFirma.builder().nombreBeneficiario(largo).build().nombreBeneficiario())
                .hasSize(40)
                .isEqualTo(largo.substring(0, 40));
    }

    @Test
    @DisplayName("Un bean nulo falla con un mensaje claro, no con NullPointerException")
    void nullBeanFailsClearly() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> CadenaOriginalBuilder.build((OrdenPagoFirma) null));
    }

    /** Extrae el campo 7 (monto), que es donde vive el formato de importe. */
    private static String amountField(BigDecimal amount) {
        String cadena = CadenaOriginalBuilder.build(OrdenPagoFirma.builder().monto(amount).build());
        return cadena.substring(2, cadena.length() - 2).split("\\|", -1)[6];
    }

    private static OrdenPagoFirma goldenOrder() {
        return OrdenPagoFirma.builder()
                .institucionContraparte(846).empresa("empresa")
                .fechaOperacion(LocalDate.of(2023, 5, 16))
                .folioOrigen("folioOrigen").claveRastreo("claveRastreo")
                .institucionOperante(90646).monto(BigDecimal.valueOf(100))
                .tipoPago(1).tipoCuentaOrdenante(3)
                .nombreOrdenante("nombreOrdenante").cuentaOrdenante("cuentaOrdenante")
                .rfcCurpOrdenante("rfcCurpOrdenante")
                .tipoCuentaBeneficiario(3).nombreBeneficiario("nombreBeneficiario")
                .cuentaBeneficiario("cuentaBeneficiario").rfcCurpBeneficiario("rfcCurpBeneficiario")
                .emailBeneficiario("emailBeneficiario")
                .tipoCuentaBeneficiario2(3).nombreBeneficiario2("nombreBeneficiario2")
                .cuentaBeneficiario2("cuentaBeneficiario2").rfcCurpBeneficiario2("rfcCurpBeneficiario2")
                .conceptoPago("conceptoPago").conceptoPago2("conceptoPago2")
                .cveCatalogoUsuario("claveCatalogoUsuario").cveCatalogoUsuario2("claveCatalogoUsuario2")
                .cvePago("clavePago").referenciaCobranza("referenciaCobranza").referenciaNumerica(12345)
                .tipoOperacion("tipoOperacion").topologia("topologia").usuario("usuario")
                .medioEntrega("medioEntrega").prioridad("prioridad").iva(BigDecimal.valueOf(0.162))
                .build();
    }
}
