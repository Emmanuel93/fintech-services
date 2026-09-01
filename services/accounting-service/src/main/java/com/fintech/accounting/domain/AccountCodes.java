package com.fintech.accounting.domain;

/** Códigos de cuenta del catálogo seed (005-seed-catalog.sql) usados por el posteo especial. */
public final class AccountCodes {
    public static final String BANCOS                 = "1101";
    public static final String CARTERA_VIGENTE        = "1201";
    public static final String INTERESES_POR_COBRAR   = "1203";
    public static final String CARTERA_CASTIGADA      = "1210";
    public static final String ESTIMACION_PREVENTIVA  = "1290";
    public static final String FONDOS_CLIENTES        = "2101";
    public static final String IVA_POR_PAGAR          = "2110";
    public static final String INGRESOS_INTERESES     = "4101";
    public static final String RECUPERACION_CASTIGADA = "4104";
    public static final String GASTO_ESTIMACION       = "5101";
    public static final String GASTO_QUEBRANTO        = "5102";
    public static final String COMISIONES_POR_PAGAR   = "2120";
    public static final String GASTO_COMISIONES       = "5104";
    /** Cuentas de ORDEN: registran la línea autorizada. Cuadran entre sí y no entran al balance. */
    public static final String LINEAS_AUTORIZADAS     = "7101";
    public static final String LINEAS_POR_DISPONER    = "7201";
    /** Memoria de lo castigado (IFRS 9 §5.4.4): el activo se da de baja, el derecho de cobro no. */
    public static final String CONTROL_CASTIGADA      = "7301";
    /** Contrapartida del asiento de apertura: incorporar saldo no es ganar dinero. */
    public static final String SALDO_INICIAL          = "3901";
    /**
     * Puentes de la conciliación bancaria, una por dirección. Un abono sin dueño se DEBE hasta
     * demostrar lo contrario (pasivo); un cargo sin aclarar es un derecho por recuperar (activo).
     * Compensarlos en una sola cuenta escondería el neto, que es lo que la puente existe para
     * evitar.
     */
    public static final String CARGOS_POR_ACLARAR     = "1109";
    public static final String DEPOSITOS_POR_IDENT    = "2109";
    public static final String OTROS_INGRESOS         = "4105";
    public static final String OTROS_GASTOS           = "5105";

    private AccountCodes() {}
}
