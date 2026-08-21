package com.fintech.stp.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catálogo de códigos de respuesta de STP al registrar una orden de pago.
 *
 * <p>Sustituye a la detección del legado, que decidía si había error con
 * {@code Integer.toString(id).length() <= 3}. Ese predicado dejaba pasar {@code -200}
 * (RECHAZO_POR_PLD, cuatro caracteres): una orden rechazada por prevención de lavado se marcaba
 * exitosa y se reportaba como dispersada. Aquí el mapeo es explícito.
 *
 * <p>Cada código lleva además su {@link Terminality}, que decide si la orden se puede reintentar o
 * si el rechazo es definitivo (regla DB-08).
 */
public enum BanxicoResponseCode {

    OTROS(0, "Otros", Terminality.TERMINAL),
    DATOS_OBLIGATORIOS(1, "Dato obligatorio", Terminality.TERMINAL),
    DATOS_NO_CATALOGADOS(2, "Dato no catalogado", Terminality.TERMINAL),
    CUENTA_NO_EMPRESA(3, "La cuenta no pertenece a la empresa", Terminality.TERMINAL),
    CUENTA_INVALIDA(4, "Cuenta inválida", Terminality.TERMINAL),
    DATOS_DUPLICADO(5, "Dato duplicado", Terminality.TERMINAL),
    CUENTA_NO_ASOCIADA(6, "Cuenta no asociada", Terminality.TERMINAL),
    CUENTA_NO_HABILITADA(7, "Cuenta no habilitada", Terminality.TERMINAL),
    RFC_CURP_INVALIDO(8, "RFC/CURP inválido", Terminality.TERMINAL),

    /** La orden ya estaba registrada de un intento anterior. Se trata como éxito idempotente. */
    CLAVE_RASTREO_DUPLICADA(-1, "Clave de rastreo duplicada", Terminality.ALREADY_REGISTERED),
    ORDEN_DUPLICADA(-2, "Orden duplicada", Terminality.ALREADY_REGISTERED),

    CLAVE_NOEXISTE_USUARIO(-3, "La clave no existe en el catálogo de usuario", Terminality.TERMINAL),
    INSTITUCION_CONTRAPARTE_OBLIGATORIA(-5, "Dato obligatorio: institución contraparte", Terminality.TERMINAL),
    EMPRESA_INSTITUCION_OPERANTE_INVALIDA(-6, "Empresa o institución operante inválida", Terminality.TERMINAL),
    CUENTA_NO_EXISTE(-7, "Cuenta no existe", Terminality.TERMINAL),
    INSTITUCION_INVALIDA(-9, "Institución inválida", Terminality.TERMINAL),
    MEDIO_ENTREGA_INVALIDO(-10, "Medio de entrega inválido", Terminality.TERMINAL),
    TIPO_CUENTA_INVALIDO(-11, "Tipo de cuenta inválido", Terminality.TERMINAL),
    TIPO_OPERACION_INVALIDA(-12, "Tipo de operación inválida", Terminality.TERMINAL),
    TIPO_PAGO_INVALIDO(-13, "Tipo de pago inválido", Terminality.TERMINAL),
    USUARIO_INVALIDO(-14, "El usuario es inválido", Terminality.TERMINAL),
    FECHA_OPERACION_INVALIDA(-16, "Fecha de operación inválida", Terminality.TERMINAL),
    NO_DETERMINAR_USUARIO(-17, "No se pudo determinar un usuario para asociar a la orden", Terminality.TERMINAL),
    INSTITUCION_OPERANTE_NO_USUARIO(-18, "La institución operante no está asociada al usuario", Terminality.TERMINAL),
    MONTO_INVALIDO(-20, "Monto inválido", Terminality.TERMINAL),
    DIGITO_VERIFICADOR_INVALIDO(-21, "Dígito verificador inválido", Terminality.TERMINAL),
    INSTITUCION_NO_COINCIDE_CLABE(-22, "La institución no coincide con la CLABE", Terminality.TERMINAL),
    LONGITUD_CLABE_INCORRECTA(-23, "Longitud de CLABE incorrecta", Terminality.TERMINAL),
    CLAVE_RASTREO_INVALIDA(-26, "Clave de rastreo inválida", Terminality.TERMINAL),

    /** STP está en modo consultas: la orden no se registró, pero se puede reintentar. */
    ENLACE_FINANCIERO_MODO_CONSULTAS(-30, "Enlace financiero en modo consultas", Terminality.RETRYABLE),

    VALOR_INVALIDO(-34, "Valor inválido. Se aceptan caracteres a-z, A-Z, 0-9", Terminality.TERMINAL),

    /** Rechazo por prevención de lavado de dinero. El legado nunca lo detectaba. */
    RECHAZO_POR_PLD(-200, "Se rechaza por PLD", Terminality.TERMINAL),

    /**
     * Código fuera del catálogo. Falla cerrado: se trata como rechazo terminal y se alerta, nunca
     * como éxito. El catálogo de STP tiene huecos (-4, -8, -15, -19, -24, -25, -27..-29, -31..-33)
     * y puede crecer sin avisarnos.
     */
    UNKNOWN(Integer.MIN_VALUE, "Código de respuesta no catalogado", Terminality.TERMINAL);

    /** Qué hacer con una orden que recibió este código. */
    public enum Terminality {
        /** Rechazo definitivo: la orden termina en REJECTED. */
        TERMINAL,
        /** Fallo transitorio: la orden vuelve a la cola con backoff. */
        RETRYABLE,
        /** La orden ya existía en STP. Se resuelve consultando su estado, no reenviándola. */
        ALREADY_REGISTERED
    }

    private static final Map<Integer, BanxicoResponseCode> BY_CODE =
            Arrays.stream(values())
                    .filter(c -> c != UNKNOWN)
                    .collect(Collectors.toMap(BanxicoResponseCode::code, Function.identity()));

    private final int code;
    private final String description;
    private final Terminality terminality;

    BanxicoResponseCode(int code, String description, Terminality terminality) {
        this.code = code;
        this.description = description;
        this.terminality = terminality;
    }

    /**
     * Resuelve un código de STP. Un código desconocido devuelve {@link #UNKNOWN}, que es TERMINAL:
     * ante la duda no se dispersa.
     */
    public static BanxicoResponseCode of(int code) {
        return BY_CODE.getOrDefault(code, UNKNOWN);
    }

    /**
     * STP devuelve un identificador positivo cuando acepta la orden, y uno de estos códigos
     * (cero o negativo) cuando la rechaza.
     */
    public static boolean isAccepted(long stpResponseId) {
        return stpResponseId > 0;
    }

    public int code() { return code; }
    public String description() { return description; }
    public Terminality terminality() { return terminality; }

    public boolean isRetryable() { return terminality == Terminality.RETRYABLE; }
    public boolean isAlreadyRegistered() { return terminality == Terminality.ALREADY_REGISTERED; }
    public boolean isTerminal() { return terminality == Terminality.TERMINAL; }
}
