package com.fintech.stp.domain.signing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Construye la cadena original que se firma para STP.
 *
 * <p>Formato: {@code ||v1|v2|...|vN||}. Sin nombres de campo, sólo valores posicionales.
 *
 * <p><strong>Por qué esta clase existe.</strong> El legado generaba la cadena por reflexión
 * ({@code ReflectionToStringBuilder} sobre {@code Class#getDeclaredFields()}), lo que dejaba el
 * contrato con Banxico dependiendo de un orden que la especificación de la JVM no garantiza, y
 * formateaba importes con un {@code DecimalFormat} sujeto al {@code Locale} por defecto — en un
 * locale con coma decimal, {@code 100.00} se vuelve {@code 100,00} y STP rechaza todas las órdenes.
 * Aquí el orden es una lista explícita y el formato está anclado a {@link Locale#ROOT}.
 *
 * <p><strong>Reglas de renderizado</strong> (replican byte a byte el comportamiento del legado):
 * <ul>
 *   <li>{@code null} → cadena vacía (produce pipes consecutivos)</li>
 *   <li>{@link LocalDate} → {@code yyyyMMdd}</li>
 *   <li>{@link BigDecimal} → dos decimales, {@link RoundingMode#HALF_EVEN}, separador {@code .}</li>
 *   <li>Cualquier otro → {@code toString()}, sin padding ni truncado</li>
 * </ul>
 */
public final class CadenaOriginalBuilder {

    private static final String PREFIX = "||";
    private static final String SEPARATOR = "|";
    private static final String SUFFIX = "||";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    /** Orden posicional CONGELADO de {@link OrdenPagoFirma}. Cambiarlo cambia todas las firmas. */
    private static final List<Function<OrdenPagoFirma, Object>> ORDEN_PAGO_FIELDS = List.of(
            OrdenPagoFirma::institucionContraparte,   //  1
            OrdenPagoFirma::empresa,                  //  2
            OrdenPagoFirma::fechaOperacion,           //  3
            OrdenPagoFirma::folioOrigen,              //  4
            OrdenPagoFirma::claveRastreo,             //  5
            OrdenPagoFirma::institucionOperante,      //  6
            OrdenPagoFirma::monto,                    //  7
            OrdenPagoFirma::tipoPago,                 //  8
            OrdenPagoFirma::tipoCuentaOrdenante,      //  9
            OrdenPagoFirma::nombreOrdenante,          // 10
            OrdenPagoFirma::cuentaOrdenante,          // 11
            OrdenPagoFirma::rfcCurpOrdenante,         // 12
            OrdenPagoFirma::tipoCuentaBeneficiario,   // 13
            OrdenPagoFirma::nombreBeneficiario,       // 14
            OrdenPagoFirma::cuentaBeneficiario,       // 15
            OrdenPagoFirma::rfcCurpBeneficiario,      // 16
            OrdenPagoFirma::emailBeneficiario,        // 17
            OrdenPagoFirma::tipoCuentaBeneficiario2,  // 18
            OrdenPagoFirma::nombreBeneficiario2,      // 19
            OrdenPagoFirma::cuentaBeneficiario2,      // 20
            OrdenPagoFirma::rfcCurpBeneficiario2,     // 21
            OrdenPagoFirma::conceptoPago,             // 22
            OrdenPagoFirma::conceptoPago2,            // 23
            OrdenPagoFirma::cveCatalogoUsuario,       // 24
            OrdenPagoFirma::cveCatalogoUsuario2,      // 25
            OrdenPagoFirma::cvePago,                  // 26
            OrdenPagoFirma::referenciaCobranza,       // 27
            OrdenPagoFirma::referenciaNumerica,       // 28
            OrdenPagoFirma::tipoOperacion,            // 29
            OrdenPagoFirma::topologia,                // 30
            OrdenPagoFirma::usuario,                  // 31
            OrdenPagoFirma::medioEntrega,             // 32
            OrdenPagoFirma::prioridad,                // 33
            OrdenPagoFirma::iva);                     // 34

    private static final List<Function<SaldoCuentaFirma, Object>> SALDO_CUENTA_FIELDS = List.of(
            SaldoCuentaFirma::empresa,
            SaldoCuentaFirma::cuentaOrdenante,
            SaldoCuentaFirma::fecha);

    private static final List<Function<ConciliacionFirma, Object>> CONCILIACION_FIELDS = List.of(
            ConciliacionFirma::empresa,
            ConciliacionFirma::tipoOrden,
            ConciliacionFirma::fechaOperacion);

    private CadenaOriginalBuilder() {
    }

    public static String build(OrdenPagoFirma firma) {
        return join(ORDEN_PAGO_FIELDS, requireNonNull(firma, "ordenPagoFirma"));
    }

    public static String build(SaldoCuentaFirma firma) {
        return join(SALDO_CUENTA_FIELDS, requireNonNull(firma, "saldoCuentaFirma"));
    }

    public static String build(ConciliacionFirma firma) {
        return join(CONCILIACION_FIELDS, requireNonNull(firma, "conciliacionFirma"));
    }

    private static <T> String join(List<Function<T, Object>> extractors, T source) {
        return extractors.stream()
                .map(extractor -> render(extractor.apply(source)))
                .collect(Collectors.joining(SEPARATOR, PREFIX, SUFFIX));
    }

    private static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate date) {
            return date.format(DATE);
        }
        if (value instanceof BigDecimal amount) {
            return amountFormat().format(amount);
        }
        return value.toString();
    }

    /**
     * {@link DecimalFormat} no es thread-safe, así que se crea uno por llamada. El costo es
     * despreciable frente a una firma RSA, y la alternativa (un {@code ThreadLocal}) añade estado
     * mutable a una clase que no debería tener ninguno.
     */
    private static DecimalFormat amountFormat() {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("El bean firmable '" + name + "' no puede ser nulo");
        }
        return value;
    }
}
