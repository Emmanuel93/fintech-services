package com.fintech.scoring.application.service;

import com.fintech.scoring.domain.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluador puro de reglas de scoring — sin dependencias de Spring.
 * Recibe una política y un reporte CDC y devuelve el resultado de la evaluación.
 */
class ScoringRuleEvaluator {

    record Result(int totalScore, boolean disqualified, List<RuleEvaluationDetail> details) {}

    Result evaluate(ScoringPolicy policy, CirculoReport report) {
        List<RuleEvaluationDetail> details = new ArrayList<>();
        int totalScore = 0;

        for (ScoringRule rule : policy.getRules()) {
            RuleEvaluationDetail detail = applyRule(rule, report);
            details.add(detail);

            if (detail.matched()) {
                totalScore += detail.scoreApplied();
                if (rule.isDisqualifying()) {
                    // Descalificante: ALTO inmediato
                    return new Result(totalScore, true, details);
                }
            }
        }

        return new Result(totalScore, false, details);
    }

    private RuleEvaluationDetail applyRule(ScoringRule rule, CirculoReport report) {
        return switch (rule.getRuleType()) {
            case MORA_CHECK      -> evalMora(rule, report);
            case FICO_THRESHOLD  -> evalFico(rule, report);
            case FICO_SCORES_COUNT -> medir(rule, report, "puntajes de buró disponibles",
                    BigDecimal.valueOf(report.getFicoScoreValor() != null ? 1 : 0));
            case CREDIT_COUNT    -> evalCreditCount(rule, report);
            case BALANCE_CHECK   -> evalBalance(rule, report);
            case INQUIRY_COUNT   -> evalInquiryCount(rule, report);

            // ── Comportamiento de pago ───────────────────────────────────────
            case WORST_ARREARS_BALANCE  -> medir(rule, report, "saldo en mora máxima histórica",
                    sumaCreditos(rule, report, CirculoCredit::getSaldoVencidoPeorAtraso));
            case OVERDUE_ACCOUNTS_COUNT -> medir(rule, report, "cuentas en mora",
                    contarCreditos(rule, report, c -> positivo(c.getSaldoVencido())));
            case CURRENT_ACCOUNTS_COUNT -> medir(rule, report, "cuentas al corriente",
                    contarCreditos(rule, report, c -> !positivo(c.getSaldoVencido())));
            case OVERDUE_PAYMENTS_COUNT -> medir(rule, report, "pagos vencidos acumulados",
                    sumaCreditos(rule, report, c -> entero(c.getNumeroPagosVencidos())));
            case ARREARS_RECENCY_MONTHS -> medir(rule, report, "meses desde el peor atraso",
                    mesesDesdePeorAtraso(rule, report));
            case PREVENTION_KEY_COUNT   -> medir(rule, report, "créditos con clave de prevención",
                    contarCreditos(rule, report, c -> tieneTexto(c.getClavePrevencion())));

            // ── Exposición y capacidad ───────────────────────────────────────
            case TOTAL_DEBT           -> medir(rule, report, "deuda total vigente",
                    sumaCreditos(rule, report, CirculoCredit::getSaldoActual));
            case CREDIT_UTILIZATION   -> medir(rule, report, "utilización de líneas (%)",
                    utilizacion(rule, report));
            case MONTHLY_PAYMENT_LOAD -> medir(rule, report, "pago mensual comprometido",
                    sumaCreditos(rule, report, CirculoCredit::getMontoPagar));
            case DEBT_TO_INCOME       -> medir(rule, report, "carga sobre el ingreso (%)",
                    cargaSobreIngreso(rule, report));

            // ── Perfil ───────────────────────────────────────────────────────
            case CREDIT_HISTORY_MONTHS -> medir(rule, report, "antigüedad del historial (meses)",
                    antiguedadHistorial(rule, report));

            // ── Persona ──────────────────────────────────────────────────────
            case AGE_YEARS         -> medir(rule, report, "edad (años)", edad(report));
            case MONTHLY_INCOME    -> medir(rule, report, "ingreso mensual", ingresoMensual(report));
            case EMPLOYMENT_MONTHS -> medir(rule, report, "antigüedad laboral (meses)",
                    antiguedadLaboral(report));
            case DEPENDENTS_COUNT  -> medir(rule, report, "dependientes económicos",
                    report.getPersonaNumDependientes() == null ? null
                            : BigDecimal.valueOf(report.getPersonaNumDependientes()));
        };
    }

    /**
     * Aplica una regla a un valor ya medido.
     *
     * <p>Las quince variables nuevas comparten forma: sacar un número del reporte, compararlo con
     * el umbral y aportar puntos. Escribir quince métodos casi idénticas sólo para variar la línea
     * del medio es donde se cuela el error que nadie ve — un operador invertido en la novena.
     *
     * <p>Un valor {@code null} es «el buró no lo trae», y entonces la regla <b>no se cumple</b>:
     * tratar un dato ausente como cero haría que quien no tiene ingreso declarado puntúe igual
     * que quien gana cero, y que un expediente incompleto pase por uno impecable.
     */
    private RuleEvaluationDetail medir(ScoringRule rule, CirculoReport report,
                                        String queMide, BigDecimal valor) {
        boolean matched = valor != null && rule.getOperator().apply(valor, rule.getThresholdValue());
        String detail = "%s%s = %s, umbral %s %s".formatted(
                queMide,
                rule.getCreditType() != null ? " (tipo " + rule.getCreditType() + ")" : "",
                valor != null ? valor.stripTrailingZeros().toPlainString() : "sin dato",
                rule.getOperator(), rule.getThresholdValue().stripTrailingZeros().toPlainString());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                rule.getCreditType(), matched,
                matched ? rule.getScoreContribution() : 0,
                rule.isDisqualifying(), detail);
    }

    // ── MORA_CHECK ────────────────────────────────────────────────────────────

    private RuleEvaluationDetail evalMora(ScoringRule rule, CirculoReport report) {
        BigDecimal worstMora = report.getCredits().stream()
                .filter(c -> matchesCreditType(rule, c.getTipoCredito()))
                .map(CirculoCredit::getPeorAtraso)
                .filter(m -> m != null)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        boolean matched = rule.getOperator().apply(worstMora, rule.getThresholdValue());
        String detail = "peor atraso=%s días, tipo=%s, umbral %s %s".formatted(
                worstMora, creditTypeLabel(rule), rule.getOperator(), rule.getThresholdValue());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                rule.getCreditType(), matched,
                matched ? rule.getScoreContribution() : 0,
                rule.isDisqualifying(), detail);
    }

    // ── FICO_THRESHOLD ────────────────────────────────────────────────────────

    private RuleEvaluationDetail evalFico(ScoringRule rule, CirculoReport report) {
        Integer ficoValor = report.getFicoScoreValor();
        BigDecimal fico = ficoValor != null ? BigDecimal.valueOf(ficoValor) : null;

        boolean matched = rule.getOperator().apply(fico, rule.getThresholdValue());
        String detail = "FICO=%s, umbral %s %s".formatted(
                ficoValor != null ? ficoValor : "N/A",
                rule.getOperator(), rule.getThresholdValue().intValue());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                null, matched,
                matched ? rule.getScoreContribution() : 0,
                false, detail);
    }

    // ── CREDIT_COUNT ──────────────────────────────────────────────────────────

    private RuleEvaluationDetail evalCreditCount(ScoringRule rule, CirculoReport report) {
        long count = report.getCredits().stream()
                .filter(c -> matchesCreditType(rule, c.getTipoCredito()))
                .count();

        boolean matched = rule.getOperator().apply(BigDecimal.valueOf(count), rule.getThresholdValue());
        String detail = "créditos tipo %s = %d, umbral %s %s".formatted(
                creditTypeLabel(rule), count, rule.getOperator(), rule.getThresholdValue().intValue());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                rule.getCreditType(), matched,
                matched ? rule.getScoreContribution() : 0,
                false, detail);
    }

    // ── BALANCE_CHECK ─────────────────────────────────────────────────────────

    private RuleEvaluationDetail evalBalance(ScoringRule rule, CirculoReport report) {
        BigDecimal sumVencido = report.getCredits().stream()
                .filter(c -> matchesCreditType(rule, c.getTipoCredito()))
                .map(c -> c.getSaldoVencido() != null ? c.getSaldoVencido() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean matched = rule.getOperator().apply(sumVencido, rule.getThresholdValue());
        String detail = "saldo vencido tipo %s = %s, umbral %s %s".formatted(
                creditTypeLabel(rule), sumVencido, rule.getOperator(), rule.getThresholdValue());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                rule.getCreditType(), matched,
                matched ? rule.getScoreContribution() : 0,
                false, detail);
    }

    // ── INQUIRY_COUNT ─────────────────────────────────────────────────────────

    private RuleEvaluationDetail evalInquiryCount(ScoringRule rule, CirculoReport report) {
        LocalDate cutoff = rule.getPeriodMonths() != null
                ? LocalDate.now().minusMonths(rule.getPeriodMonths())
                : LocalDate.MIN;

        long count = report.getInquiries().stream()
                .filter(i -> i.getFechaConsulta() != null && !i.getFechaConsulta().isBefore(cutoff))
                .count();

        boolean matched = rule.getOperator().apply(BigDecimal.valueOf(count), rule.getThresholdValue());
        String detail = "consultas en %d meses = %d, umbral %s %s".formatted(
                rule.getPeriodMonths() != null ? rule.getPeriodMonths() : 0,
                count, rule.getOperator(), rule.getThresholdValue().intValue());

        return new RuleEvaluationDetail(rule.getRuleId(), rule.getRuleType().name(),
                null, matched,
                matched ? rule.getScoreContribution() : 0,
                false, detail);
    }

    // ── Medidas sobre el reporte ──────────────────────────────────────────────

    /** Suma un campo numérico sobre los créditos que la regla alcanza. */
    private BigDecimal sumaCreditos(ScoringRule rule, CirculoReport report,
                                     java.util.function.Function<CirculoCredit, BigDecimal> campo) {
        return creditos(rule, report)
                .map(campo)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal contarCreditos(ScoringRule rule, CirculoReport report,
                                       java.util.function.Predicate<CirculoCredit> cumple) {
        return BigDecimal.valueOf(creditos(rule, report).filter(cumple).count());
    }

    private java.util.stream.Stream<CirculoCredit> creditos(ScoringRule rule, CirculoReport report) {
        return report.getCredits().stream()
                .filter(c -> matchesCreditType(rule, c.getTipoCredito()));
    }

    /**
     * Meses transcurridos desde el peor atraso más <b>reciente</b>.
     *
     * <p>El más reciente y no el más antiguo: lo que importa es cuánto hace que dejó de tropezar.
     * Con el más antiguo, alguien que se atrasó hace ocho años y otra vez el mes pasado saldría
     * con ocho años de buena conducta.
     */
    private BigDecimal mesesDesdePeorAtraso(ScoringRule rule, CirculoReport report) {
        LocalDate masReciente = creditos(rule, report)
                .map(CirculoCredit::getFechaPeorAtraso)
                .filter(f -> f != null)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (masReciente == null) return null;   // nunca se atrasó: no es cero meses, es sin dato
        return BigDecimal.valueOf(
                java.time.temporal.ChronoUnit.MONTHS.between(masReciente, LocalDate.now()));
    }

    /** Saldo usado sobre línea autorizada, en porcentaje. */
    private BigDecimal utilizacion(ScoringRule rule, CirculoReport report) {
        BigDecimal saldo = sumaCreditos(rule, report, CirculoCredit::getSaldoActual);
        BigDecimal linea = sumaCreditos(rule, report, CirculoCredit::getLimiteCredito);
        // Sin línea autorizada no hay proporción que calcular. Devolver cero diría «no usa nada»,
        // que es lo contrario de lo que significa un expediente sin líneas revolventes.
        if (linea.signum() == 0) return null;
        return saldo.multiply(BigDecimal.valueOf(100))
                .divide(linea, 2, java.math.RoundingMode.HALF_UP);
    }

    /** Pago mensual comprometido sobre ingreso declarado, en porcentaje. */
    private BigDecimal cargaSobreIngreso(ScoringRule rule, CirculoReport report) {
        BigDecimal ingreso = ingresoMensual(report);
        if (ingreso == null || ingreso.signum() == 0) return null;
        BigDecimal pago = sumaCreditos(rule, report, CirculoCredit::getMontoPagar);
        return pago.multiply(BigDecimal.valueOf(100))
                .divide(ingreso, 2, java.math.RoundingMode.HALF_UP);
    }

    /** Meses desde la apertura más antigua: cuánto lleva existiendo en el buró. */
    private BigDecimal antiguedadHistorial(ScoringRule rule, CirculoReport report) {
        LocalDate primera = creditos(rule, report)
                .map(CirculoCredit::getFechaApertura)
                .filter(f -> f != null)
                .min(LocalDate::compareTo)
                .orElse(null);
        if (primera == null) return null;
        return BigDecimal.valueOf(
                java.time.temporal.ChronoUnit.MONTHS.between(primera, LocalDate.now()));
    }

    private BigDecimal edad(CirculoReport report) {
        LocalDate nacimiento = report.getPersonaFechaNacimiento();
        if (nacimiento == null) return null;
        return BigDecimal.valueOf(
                java.time.temporal.ChronoUnit.YEARS.between(nacimiento, LocalDate.now()));
    }

    /** El sueldo del empleo más reciente que reporta el buró. */
    private BigDecimal ingresoMensual(CirculoReport report) {
        return empleoMasReciente(report)
                .map(CirculoEmployment::getSalarioMensual)
                .filter(v -> v != null)
                .orElse(null);
    }

    private BigDecimal antiguedadLaboral(CirculoReport report) {
        return empleoMasReciente(report)
                .map(CirculoEmployment::getFechaContratacion)
                .filter(f -> f != null)
                .map(f -> BigDecimal.valueOf(
                        java.time.temporal.ChronoUnit.MONTHS.between(f, LocalDate.now())))
                .orElse(null);
    }

    /**
     * El empleo vigente, o el último si ninguno lo está.
     *
     * <p>Vigente es el que no tiene fecha de último día. Ordenar sólo por fecha de contratación
     * tomaría un empleo terminado si fue el último en empezar, y entonces «antigüedad laboral»
     * mediría un trabajo que ya no tiene.
     */
    private java.util.Optional<CirculoEmployment> empleoMasReciente(CirculoReport report) {
        return report.getEmployments().stream()
                .max(java.util.Comparator
                        .comparing((CirculoEmployment e) -> e.getFechaUltimoDia() == null)
                        .thenComparing(e -> e.getFechaContratacion() == null
                                ? LocalDate.MIN : e.getFechaContratacion()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean positivo(BigDecimal v) { return v != null && v.signum() > 0; }

    private static boolean tieneTexto(String s) { return s != null && !s.isBlank(); }

    private static BigDecimal entero(Integer v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    private boolean matchesCreditType(ScoringRule rule, String creditType) {
        return rule.getCreditType() == null
                || rule.getCreditType().equalsIgnoreCase(creditType);
    }

    private String creditTypeLabel(ScoringRule rule) {
        return rule.getCreditType() != null ? rule.getCreditType() : "todos";
    }
}
