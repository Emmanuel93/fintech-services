package com.fintech.scoring.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Las variables del buró que una política puede evaluar.
 *
 * <p>El catálogo era de cinco y sólo miraba lo obvio —peor atraso, FICO, número de créditos—,
 * mientras el reporte de Círculo traía treinta campos por crédito que nadie leía. El más notorio:
 * {@code saldoVencidoPeorAtraso}, el importe que el cliente llegó a deber en su peor momento, que
 * es la diferencia entre alguien que se atrasó con dos mil pesos y alguien que se atrasó con
 * doscientos mil. Los dos daban el mismo score.
 *
 * <p>Cada variable declara <b>qué mide y en qué unidad</b>, no sólo su nombre. Eso es lo que
 * permite que la pantalla de alta de políticas se construya sola a partir de este catálogo: sin
 * los metadatos, cada variable nueva exigiría tocar el formulario, y un catálogo que hay que
 * mantener en dos lados termina desalineado.
 *
 * <p>Las unidades importan al validar: un umbral de 90 en días es un trimestre y en pesos es
 * nada. La pantalla usa {@link #unit()} para rotular el campo y elegir el formato.
 */
public enum RuleType {

    // ── Comportamiento de pago ───────────────────────────────────────────────

    /** Peor atraso alcanzado, en días ({@code peorAtraso}). */
    WORST_ARREARS_DAYS(Dimension.BEHAVIOR, "Peor atraso histórico", Unit.DAYS,
            "Los días de atraso más altos que registra el buró, en cualquier crédito.", true, false),

    /**
     * Saldo que estaba vencido cuando ocurrió el peor atraso ({@code saldoVencidoPeorAtraso}).
     *
     * <p>Es la severidad de la peor caída, no su duración. Sin esto, atrasarse noventa días con
     * dos mil pesos y con doscientos mil pesa exactamente igual.
     */
    WORST_ARREARS_BALANCE(Dimension.BEHAVIOR, "Saldo en mora máxima histórica", Unit.PESOS,
            "Cuánto llegó a deber vencido en su peor momento. Mide la severidad, no la duración.", true, false),

    /** Saldo vencido hoy ({@code saldoVencido}), sumado. */
    BALANCE_CHECK(Dimension.BEHAVIOR, "Saldo en mora actual", Unit.PESOS,
            "Lo que debe vencido hoy, sumando todos sus créditos.", true, false),

    /** Cuántos créditos traen saldo vencido hoy. */
    OVERDUE_ACCOUNTS_COUNT(Dimension.BEHAVIOR, "Cuentas en mora", Unit.ACCOUNTS,
            "Cuántos de sus créditos están vencidos hoy. Uno con mora no es lo mismo que cinco.", true, false),

    /** Cuántos créditos están al corriente. */
    CURRENT_ACCOUNTS_COUNT(Dimension.BEHAVIOR, "Cuentas al corriente", Unit.ACCOUNTS,
            "Cuántos créditos paga sin atraso. Es la señal positiva que la mora sola no da.", true, false),

    /** Suma de mensualidades vencidas ({@code numeroPagosVencidos}). */
    OVERDUE_PAYMENTS_COUNT(Dimension.BEHAVIOR, "Pagos vencidos acumulados", Unit.PAYMENTS,
            "Cuántas mensualidades lleva sin cubrir, sumando todos sus créditos.", true, false),

    /**
     * Meses transcurridos desde el peor atraso ({@code fechaPeorAtraso}).
     *
     * <p>Un tropiezo de hace seis años no dice lo mismo que uno de hace tres meses, y sin esta
     * variable la política los castiga igual para siempre.
     */
    ARREARS_RECENCY_MONTHS(Dimension.BEHAVIOR, "Antigüedad del peor atraso", Unit.MONTHS,
            "Cuánto tiempo pasó desde su peor atraso. Un tropiezo viejo pesa distinto que uno reciente.", true, false),

    /** Créditos con clave de prevención (quita, fraude, cuenta cedida…). */
    PREVENTION_KEY_COUNT(Dimension.BEHAVIOR, "Créditos con clave de prevención", Unit.ACCOUNTS,
            "Marcas del otorgante: quita, fraude, cuenta cedida. Suelen ser descalificantes.", true, false),

    // ── Exposición y capacidad de pago ───────────────────────────────────────

    /** Deuda total vigente ({@code saldoActual}), sumada. */
    TOTAL_DEBT(Dimension.EXPOSURE, "Deuda total vigente", Unit.PESOS,
            "La suma de lo que debe hoy, esté al corriente o no.", true, false),

    /** Saldo sobre línea autorizada, en porcentaje. */
    CREDIT_UTILIZATION(Dimension.EXPOSURE, "Utilización de sus líneas", Unit.PERCENT,
            "Qué proporción de su crédito autorizado ya usó. Cerca del tope señala estrés.", true, false),

    /** Suma de mensualidades comprometidas ({@code montoPagar}). */
    MONTHLY_PAYMENT_LOAD(Dimension.EXPOSURE, "Pago mensual comprometido", Unit.PESOS,
            "Cuánto se le va cada mes en los créditos que ya tiene.", true, false),

    /** Pago mensual comprometido sobre ingreso declarado, en porcentaje. */
    DEBT_TO_INCOME(Dimension.EXPOSURE, "Carga sobre su ingreso", Unit.PERCENT,
            "Qué parte de su sueldo ya está comprometida en pagos. Requiere ingreso en el expediente.", false, false),

    // ── Perfil crediticio ────────────────────────────────────────────────────

    /** Puntaje FICO del buró. */
    FICO_THRESHOLD(Dimension.PROFILE, "Puntaje FICO", Unit.POINTS,
            "El score que calcula el propio buró con su modelo.", false, false),

    /**
     * Si el buró trae puntaje, contado como 1 o 0.
     *
     * <p>Existe porque {@link #FICO_THRESHOLD} no puede expresar la <em>ausencia</em>: los
     * operadores comparan contra un umbral y un valor nulo nunca cumple ninguno, así que
     * «sin score» se comportaba igual que «score de cero puntos aportados» — sumaba nada y
     * seguía adelante. Con el resto de las reglas puntuando ingreso, empleo y edad, eso bastaba
     * para que alguien sin historial en buró saliera aprobado solo.
     *
     * <p>No juzga el puntaje: dice si existe. Quien decide qué hacer con esa ausencia es la
     * política, poniéndole {@code is_disqualifying}.
     */
    FICO_SCORES_COUNT(Dimension.PROFILE, "Puntajes de buró disponibles", Unit.ACCOUNTS,
            "Si el reporte trae puntaje del buró (1) o no (0). Sirve para exigir que exista.",
            false, false),

    /** Número de créditos del tipo configurado. */
    CREDIT_COUNT(Dimension.PROFILE, "Número de créditos", Unit.ACCOUNTS,
            "Cuántos créditos tiene. Se puede acotar a un tipo (TC, FM, PP…).", true, false),

    /** Meses desde la apertura más antigua. */
    CREDIT_HISTORY_MONTHS(Dimension.PROFILE, "Antigüedad de su historial", Unit.MONTHS,
            "Desde cuándo existe en el buró. Un historial corto se conoce menos, no es peor.", true, false),

    /** Consultas al buró en una ventana de meses. */
    INQUIRY_COUNT(Dimension.PROFILE, "Consultas al buró", Unit.INQUIRIES,
            "Cuántas veces lo consultaron en la ventana que configures. Muchas seguidas señalan búsqueda de crédito.", false, true),

    // ── Perfil de la persona ─────────────────────────────────────────────────

    /** Edad en años, desde la fecha de nacimiento del reporte. */
    AGE_YEARS(Dimension.PERSON, "Edad", Unit.YEARS,
            "Años cumplidos según el buró.", false, false),

    /** Ingreso mensual verificado del empleo más reciente. */
    MONTHLY_INCOME(Dimension.PERSON, "Ingreso mensual", Unit.PESOS,
            "El sueldo del empleo más reciente que reporta el buró.", false, false),

    /** Antigüedad en el empleo actual, en meses. */
    EMPLOYMENT_MONTHS(Dimension.PERSON, "Antigüedad en el empleo", Unit.MONTHS,
            "Cuánto lleva en su trabajo actual.", false, false),

    /** Número de dependientes económicos declarados. */
    DEPENDENTS_COUNT(Dimension.PERSON, "Dependientes económicos", Unit.PEOPLE,
            "Cuántas personas dependen de su ingreso.", false, false);

    /** En qué agrupa la pantalla la variable, para no presentar veinte campos en una lista plana. */
    public enum Dimension { BEHAVIOR, EXPOSURE, PROFILE, PERSON }

    /** Qué se está midiendo. Define el rótulo del umbral y su formato. */
    public enum Unit { DAYS, MONTHS, YEARS, PESOS, PERCENT, POINTS, ACCOUNTS, PAYMENTS, INQUIRIES, PEOPLE }

    private final Dimension dimension;
    private final String etiqueta;
    private final Unit unidad;
    private final String explicacion;
    /** Si tiene sentido acotarla a un tipo de crédito del buró (TC, FM, PP…). */
    private final boolean aplicaTipoCredito;
    /** Si necesita una ventana de tiempo en meses. */
    private final boolean aplicaPeriodo;

    RuleType(Dimension dimension, String etiqueta, Unit unidad, String explicacion,
             boolean aplicaTipoCredito, boolean aplicaPeriodo) {
        this.dimension = dimension;
        this.etiqueta = etiqueta;
        this.unidad = unidad;
        this.explicacion = explicacion;
        this.aplicaTipoCredito = aplicaTipoCredito;
        this.aplicaPeriodo = aplicaPeriodo;
    }

    public Dimension dimension()      { return dimension; }
    public String    label()       { return etiqueta; }
    public Unit    unit()         { return unidad; }
    public String    explanation()    { return explicacion; }
    public boolean   appliesToCreditType() { return aplicaTipoCredito; }
    public boolean   appliesToPeriod()  { return aplicaPeriodo; }

    public static List<RuleType> catalog() {
        return Arrays.asList(values());
    }
}
