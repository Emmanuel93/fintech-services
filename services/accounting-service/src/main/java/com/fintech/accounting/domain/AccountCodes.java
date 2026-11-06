package com.fintech.accounting.domain;

/** Códigos de cuenta del catálogo seed (005-seed-catalog.sql) usados por el posteo especial. */
public final class AccountCodes {
    public static final String BANCOS                 = "1101";
    /** Collections the portfolio applied that the bank has not reported yet (SPEI operation date still ahead). */
    public static final String REMESAS_EN_CAMINO      = "1103";
    /** What payers paid that no loan owed: owed back to them until applied or refunded. */
    public static final String SALDOS_A_FAVOR         = "2102";
    /** Dispositions (and other SPEI orders) booked but not yet reported out by the bank. */
    public static final String SPEI_POR_LIQUIDAR      = "2103";
    public static final String CARTERA_VIGENTE        = "1201";
    public static final String INTERESES_POR_COBRAR   = "1203";
    public static final String CARTERA_CASTIGADA      = "1210";
    public static final String ESTIMACION_PREVENTIVA  = "1290";
    public static final String FONDOS_CLIENTES        = "2101";
    public static final String IVA_POR_PAGAR          = "2110";
    public static final String INGRESOS_INTERESES     = "4101";
    public static final String INGRESOS_MORATORIOS    = "4102";
    public static final String INGRESOS_COMISIONES    = "4103";
    /** Condonación de lo devengado: gasto, no cancelación de ingreso (el ingreso sí se ganó). */
    public static final String GASTO_CONDONACIONES    = "5103";
    public static final String RECUPERACION_CASTIGADA = "4104";
    public static final String GASTO_ESTIMACION       = "5101";
    public static final String GASTO_QUEBRANTO        = "5102";
    public static final String COMISIONES_POR_PAGAR   = "2120";
    /** IVA a beneficiary invoiced on a commission, once the commission was paid (creditable on a cash basis). */
    public static final String IVA_ACREDITABLE_PAGADO = "1140";
    /** IVA a beneficiary invoiced on a commission not paid yet. */
    public static final String IVA_PENDIENTE_ACREDITAR = "1141";
    /** ISR withheld from a natural person's commission, owed to the SAT. */
    public static final String ISR_RETENIDO_POR_ENTERAR = "2111";
    /** Two thirds of the IVA of a natural person's commission, withheld and owed to the SAT. */
    public static final String IVA_RETENIDO_POR_ENTERAR = "2112";
    /** Opening fees charged and not yet recognized: deferred over the life of the loan (IFRS 9). */
    public static final String COMISIONES_POR_DEVENGAR = "2150";
    /** Transaction costs of origination (the promoter's commission) not yet amortized (IFRS 9 B5.4.8). */
    public static final String COSTOS_ORIGINACION = "1205";
    /** Interest revenue from the fees and costs inside the effective interest rate, apart from contractual 4101. */
    public static final String INGRESOS_TASA_EFECTIVA = "4106";
    public static final String GASTO_COMISIONES       = "5104";
    /** Cuentas de ORDEN: registran la línea autorizada. Cuadran entre sí y no entran al balance. */
    public static final String LINEAS_AUTORIZADAS     = "7101";
    public static final String LINEAS_POR_DISPONER    = "7201";
    /** Memoria de lo castigado (IFRS 9 §5.4.4): el activo se da de baja, el derecho de cobro no. */
    public static final String CONTROL_CASTIGADA      = "7301";
    /** Stage 3 interest not recognized as revenue (memo, IFRS 9 net basis) and its contra. */
    public static final String INTERESES_ETAPA3_ORDEN = "7401";
    public static final String CONTRA_INTERESES_ETAPA3 = "7402";
    /** Paid-in share capital (IAS 32 equity): the shareholders' contributions the meeting agreed. */
    public static final String CAPITAL_SOCIAL         = "3101";
    /** Retained earnings: the result of the years already closed (the year-end close brings it here). */
    public static final String RESULTADO_EJERCICIOS_ANTERIORES = "3201";
    /** Funding lines drawn: a financial liability at amortised cost (IFRS 9 4.2.1). */
    public static final String PRESTAMOS_FONDEO       = "2201";
    /** Interest accrued on the funding lines and not yet paid. */
    public static final String INTERESES_FONDEO_POR_PAGAR = "2202";
    /** The lines' transaction costs (opening fee) deducted from the liability and amortized (debit balance). */
    public static final String COSTOS_FONDEO_POR_AMORTIZAR = "2209";
    /** Interest expense of the funding lines, contractual and the amortized transaction costs (effective rate). */
    public static final String GASTO_INTERESES_FONDEO = "5201";
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
