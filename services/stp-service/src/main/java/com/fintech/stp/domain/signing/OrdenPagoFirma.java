package com.fintech.stp.domain.signing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bean firmable de una orden de pago SPEI.
 *
 * <p><strong>El orden de declaración de los componentes es el contrato con STP.</strong> La cadena
 * original se arma posicionalmente con esos 34 valores (ver {@link CadenaOriginalBuilder}); mover,
 * añadir o quitar uno cambia todas las firmas y STP rechaza la orden.
 *
 * <p>Es un record inmutable a propósito: el legado usaba un POJO mutable recorrido por reflexión, y
 * el orden dependía de {@code Class#getDeclaredFields()}, que la JVM no garantiza. Aquí el orden es
 * explícito y vive en {@code CadenaOriginalBuilder#ORDEN_PAGO_FIELDS}.
 */
public record OrdenPagoFirma(
        Integer institucionContraparte,   //  1
        String empresa,                   //  2
        LocalDate fechaOperacion,         //  3
        String folioOrigen,               //  4
        String claveRastreo,              //  5
        Integer institucionOperante,      //  6
        BigDecimal monto,                 //  7
        Integer tipoPago,                 //  8
        Integer tipoCuentaOrdenante,      //  9
        String nombreOrdenante,           // 10
        String cuentaOrdenante,           // 11
        String rfcCurpOrdenante,          // 12
        Integer tipoCuentaBeneficiario,   // 13
        String nombreBeneficiario,        // 14
        String cuentaBeneficiario,        // 15
        String rfcCurpBeneficiario,       // 16
        String emailBeneficiario,         // 17
        Integer tipoCuentaBeneficiario2,  // 18
        String nombreBeneficiario2,       // 19
        String cuentaBeneficiario2,       // 20
        String rfcCurpBeneficiario2,      // 21
        String conceptoPago,              // 22
        String conceptoPago2,             // 23
        String cveCatalogoUsuario,        // 24
        String cveCatalogoUsuario2,       // 25
        String cvePago,                   // 26
        String referenciaCobranza,        // 27
        Integer referenciaNumerica,       // 28
        String tipoOperacion,             // 29
        String topologia,                 // 30
        String usuario,                   // 31
        String medioEntrega,              // 32
        String prioridad,                 // 33
        BigDecimal iva                    // 34
) {

    /** Longitud máxima que STP acepta en el nombre del beneficiario. */
    public static final int MAX_NOMBRE_BENEFICIARIO = 40;

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Constructor por pasos. Existe porque 34 argumentos posicionales en la línea de llamada son
     * ilegibles y un error de orden ahí no lo detecta el compilador (muchos son String).
     */
    public static final class Builder {
        private Integer institucionContraparte;
        private String empresa;
        private LocalDate fechaOperacion;
        private String folioOrigen;
        private String claveRastreo;
        private Integer institucionOperante;
        private BigDecimal monto;
        private Integer tipoPago;
        private Integer tipoCuentaOrdenante;
        private String nombreOrdenante;
        private String cuentaOrdenante;
        private String rfcCurpOrdenante;
        private Integer tipoCuentaBeneficiario;
        private String nombreBeneficiario;
        private String cuentaBeneficiario;
        private String rfcCurpBeneficiario;
        private String emailBeneficiario;
        private Integer tipoCuentaBeneficiario2;
        private String nombreBeneficiario2;
        private String cuentaBeneficiario2;
        private String rfcCurpBeneficiario2;
        private String conceptoPago;
        private String conceptoPago2;
        private String cveCatalogoUsuario;
        private String cveCatalogoUsuario2;
        private String cvePago;
        private String referenciaCobranza;
        private Integer referenciaNumerica;
        private String tipoOperacion;
        private String topologia;
        private String usuario;
        private String medioEntrega;
        private String prioridad;
        private BigDecimal iva;

        public Builder institucionContraparte(Integer v) { this.institucionContraparte = v; return this; }
        public Builder empresa(String v) { this.empresa = v; return this; }
        public Builder fechaOperacion(LocalDate v) { this.fechaOperacion = v; return this; }
        public Builder folioOrigen(String v) { this.folioOrigen = v; return this; }
        public Builder claveRastreo(String v) { this.claveRastreo = v; return this; }
        public Builder institucionOperante(Integer v) { this.institucionOperante = v; return this; }
        public Builder monto(BigDecimal v) { this.monto = v; return this; }
        public Builder tipoPago(Integer v) { this.tipoPago = v; return this; }
        public Builder tipoCuentaOrdenante(Integer v) { this.tipoCuentaOrdenante = v; return this; }
        public Builder nombreOrdenante(String v) { this.nombreOrdenante = v; return this; }
        public Builder cuentaOrdenante(String v) { this.cuentaOrdenante = v; return this; }
        public Builder rfcCurpOrdenante(String v) { this.rfcCurpOrdenante = v; return this; }
        public Builder tipoCuentaBeneficiario(Integer v) { this.tipoCuentaBeneficiario = v; return this; }
        public Builder cuentaBeneficiario(String v) { this.cuentaBeneficiario = v; return this; }
        public Builder rfcCurpBeneficiario(String v) { this.rfcCurpBeneficiario = v; return this; }
        public Builder emailBeneficiario(String v) { this.emailBeneficiario = v; return this; }
        public Builder tipoCuentaBeneficiario2(Integer v) { this.tipoCuentaBeneficiario2 = v; return this; }
        public Builder nombreBeneficiario2(String v) { this.nombreBeneficiario2 = v; return this; }
        public Builder cuentaBeneficiario2(String v) { this.cuentaBeneficiario2 = v; return this; }
        public Builder rfcCurpBeneficiario2(String v) { this.rfcCurpBeneficiario2 = v; return this; }
        public Builder conceptoPago(String v) { this.conceptoPago = v; return this; }
        public Builder conceptoPago2(String v) { this.conceptoPago2 = v; return this; }
        public Builder cveCatalogoUsuario(String v) { this.cveCatalogoUsuario = v; return this; }
        public Builder cveCatalogoUsuario2(String v) { this.cveCatalogoUsuario2 = v; return this; }
        public Builder cvePago(String v) { this.cvePago = v; return this; }
        public Builder referenciaCobranza(String v) { this.referenciaCobranza = v; return this; }
        public Builder referenciaNumerica(Integer v) { this.referenciaNumerica = v; return this; }
        public Builder tipoOperacion(String v) { this.tipoOperacion = v; return this; }
        public Builder topologia(String v) { this.topologia = v; return this; }
        public Builder usuario(String v) { this.usuario = v; return this; }
        public Builder medioEntrega(String v) { this.medioEntrega = v; return this; }
        public Builder prioridad(String v) { this.prioridad = v; return this; }
        public Builder iva(BigDecimal v) { this.iva = v; return this; }

        /**
         * Trunca a {@value #MAX_NOMBRE_BENEFICIARIO} caracteres, que es lo que valida STP.
         *
         * <p>El legado truncaba <em>después</em> de persistir, así que la BD guardaba el nombre
         * completo y STP recibía 40 — y luego la comparación contra el {@code nombreCep} del acuse
         * fallaba siempre para nombres largos. Aquí se trunca en el único punto donde importa: el
         * valor que entra a la cadena firmada. Quien persiste guarda ambos.
         */
        public Builder nombreBeneficiario(String v) {
            this.nombreBeneficiario = truncarNombreBeneficiario(v);
            return this;
        }

        public OrdenPagoFirma build() {
            return new OrdenPagoFirma(
                    institucionContraparte, empresa, fechaOperacion, folioOrigen, claveRastreo,
                    institucionOperante, monto, tipoPago, tipoCuentaOrdenante, nombreOrdenante,
                    cuentaOrdenante, rfcCurpOrdenante, tipoCuentaBeneficiario, nombreBeneficiario,
                    cuentaBeneficiario, rfcCurpBeneficiario, emailBeneficiario,
                    tipoCuentaBeneficiario2, nombreBeneficiario2, cuentaBeneficiario2,
                    rfcCurpBeneficiario2, conceptoPago, conceptoPago2, cveCatalogoUsuario,
                    cveCatalogoUsuario2, cvePago, referenciaCobranza, referenciaNumerica,
                    tipoOperacion, topologia, usuario, medioEntrega, prioridad, iva);
        }
    }

    public static String truncarNombreBeneficiario(String nombre) {
        if (nombre == null || nombre.length() <= MAX_NOMBRE_BENEFICIARIO) {
            return nombre;
        }
        return nombre.substring(0, MAX_NOMBRE_BENEFICIARIO);
    }
}
