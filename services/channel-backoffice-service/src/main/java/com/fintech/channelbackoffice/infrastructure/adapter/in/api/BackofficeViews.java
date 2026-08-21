package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditProductClient.CreditProductResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationDetailResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.RuleView;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.ScoringPolicyView;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient.ThresholdView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Traducción única al contrato que consume el frontend (shared-types). Centralizada
 * aquí para que la misma entidad se vea igual en cartera, clientes y tablero — y no
 * derive entre controladores.
 */
final class BackofficeViews {

    private BackofficeViews() {}

    // ── Cliente (shared-types Client) ─────────────────────────────────────────

    static Map<String, Object> client(PartyResponse p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partyId", p.partyId() == null ? null : p.partyId().toString());
        m.put("partyType", p.partyType());
        m.put("status", p.status());
        m.put("fullName", fullName(p));
        m.put("curp", p.curp());
        m.put("rfc", p.rfc());
        m.put("riskLevel", p.riskLevel());
        m.put("totalScore", p.totalScore());
        m.put("assignedExecutiveId",
                p.assignedExecutiveId() == null ? null : p.assignedExecutiveId().toString());
        m.put("assignedExecutiveName", p.assignedExecutiveName());
        m.put("createdAt", p.createdAt() == null ? null : p.createdAt().toString());
        return m;
    }

    static String fullName(PartyResponse p) {
        String full = Stream.of(p.firstName(), p.lastName1(), p.lastName2())
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + " " + b).orElse("");
        return full.isBlank() ? null : full;
    }

    // ── Cuenta de crédito (shared-types CreditAccount / AccountDetail) ─────────

    static Map<String, Object> account(CreditAccountResponse a, String obligorName) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null) return m;
        m.put("creditAccountId", a.creditAccountId() == null ? null : a.creditAccountId().toString());
        m.put("contractNumber", a.contractNumber());
        m.put("obligorPartyId", a.obligorPartyId() == null ? null : a.obligorPartyId().toString());
        m.put("obligorName", obligorName);
        m.put("productCode", a.productCode());
        m.put("productType", a.productType());
        m.put("productBehavior", a.productBehavior());
        m.put("status", a.status());
        m.put("nominalRate", a.nominalRate());
        m.put("assignedTerm", a.assignedTerm());
        m.put("principalBalance", a.principalBalance());
        m.put("accruedInterestBalance", a.accruedInterestBalance());
        m.put("penaltyBalance", a.penaltyBalance());
        m.put("ivaBalance", a.ivaBalance());
        m.put("creditLimit", a.creditLimit());
        m.put("availableCredit", a.availableCredit());
        m.put("totalDebt", totalDebt(a));
        m.put("daysDelinquent", a.daysDelinquent());
        m.put("riskTier", a.riskTier());
        m.put("ifrs9Stage", ifrs9Stage(a.daysDelinquent()));
        m.put("clabeAccount", a.clabeAccount());
        m.put("activatedAt", a.activatedAt() == null ? null : a.activatedAt().toString());
        // Asignación de ejecutivo: su propia slice.
        m.put("assignedExecutiveId", null);
        m.put("assignedExecutiveName", null);
        // Avance del plan de pagos — lo que convierte una fila en un seguimiento.
        m.put("paidInstallments", a.paidInstallments());
        m.put("totalInstallments", a.totalInstallments());
        m.put("nextInstallmentNumber", a.nextInstallmentNumber());
        m.put("paymentDueDate", a.paymentDueDate() == null ? null : a.paymentDueDate().toString());
        m.put("minimumPayment", a.minimumPayment());
        m.put("principalPaid", a.principalPaid());
        m.put("overdueAmount", a.overdueAmount());
        m.put("overdueInstallments", a.overdueInstallments());
        return m;
    }

    // ── Solicitud (shared-types CreditApplication) ────────────────────────────

    static Map<String, Object> application(ApplicationResponse a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", a.applicationId() == null ? null : a.applicationId().toString());
        // El folio es lo que se busca y se dicta; el UUID no se le pide a nadie.
        m.put("folio", a.folio());
        m.put("prospectId", a.prospectId() == null ? null : a.prospectId().toString());
        // El nombre lo resuelve el controlador en lote y lo inyecta después: una
        // consulta a party por fila convertiría la bandeja en N+1.
        m.put("prospectName", null);
        m.put("productType", a.productType());
        m.put("requestedAmount", a.requestedAmount());
        m.put("requestedTerm", a.requestedTerm());
        m.put("status", a.status());
        m.put("approvalFlow", a.approvalFlow());
        m.put("riskLevel", a.riskLevel());
        m.put("decision", a.decision());
        m.put("decidedBy", a.decidedBy());
        m.put("rejectionReason", a.rejectionReason());
        m.put("offeredAmount", a.offeredAmount());
        m.put("offeredLine", a.offeredLine());
        m.put("offeredTerm", a.offeredTerm());
        m.put("nominalRate", a.nominalRate());
        m.put("cat", a.cat());
        m.put("createdAt", a.createdAt() == null ? null : a.createdAt().toString());
        return m;
    }

    /**
     * Detalle de una solicitud para la mesa de análisis: superset de {@link #application}
     * con oferta completa, contrato y traza de decisión. La bandeja usa la vista corta;
     * la ficha, ésta.
     */
    static Map<String, Object> applicationDetail(ApplicationDetailResponse a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null) return m;
        m.put("applicationId", a.applicationId() == null ? null : a.applicationId().toString());
        m.put("prospectId", a.prospectId() == null ? null : a.prospectId().toString());
        m.put("prospectType", a.prospectType());
        m.put("productType", a.productType());
        m.put("productCode", a.productCode());
        m.put("productBehavior", a.productBehavior());
        m.put("status", a.status());
        m.put("requestedAmount", a.requestedAmount());
        m.put("requestedTerm", a.requestedTerm());
        m.put("scoreRequestId", a.scoreRequestId() == null ? null : a.scoreRequestId().toString());
        m.put("riskLevel", a.riskLevel());
        m.put("decision", a.decision());
        m.put("approvalFlow", a.approvalFlow());
        m.put("decidedBy", a.decidedBy());
        m.put("rejectionReason", a.rejectionReason());
        m.put("rejectedAt", a.rejectedAt() == null ? null : a.rejectedAt().toString());
        m.put("offeredAmount", a.offeredAmount());
        m.put("offeredLine", a.offeredLine());
        m.put("offeredTerm", a.offeredTerm());
        m.put("nominalRate", a.nominalRate());
        m.put("cat", a.cat());
        m.put("validUntil", a.validUntil() == null ? null : a.validUntil().toString());
        m.put("offerPresentedAt", a.offerPresentedAt() == null ? null : a.offerPresentedAt().toString());
        m.put("offerAcceptedAt", a.offerAcceptedAt() == null ? null : a.offerAcceptedAt().toString());
        m.put("contractNumber", a.contractNumber());
        m.put("signatureMethod", a.signatureMethod());
        m.put("clabeAccount", a.clabeAccount());
        m.put("documentRef", a.documentRef());
        m.put("contractSignedAt", a.contractSignedAt() == null ? null : a.contractSignedAt().toString());
        m.put("createdAt", a.createdAt() == null ? null : a.createdAt().toString());
        m.put("updatedAt", a.updatedAt() == null ? null : a.updatedAt().toString());
        return m;
    }

    // ── Producto (shared-types ProductDefinition) ─────────────────────────────

    static Map<String, Object> product(CreditProductResponse p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.productDefinitionId() == null ? null : p.productDefinitionId().toString());
        m.put("productCode", p.productCode());
        m.put("productVersion", p.productVersion());
        m.put("productType", p.productType());
        m.put("behavior", p.behavior());
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("status", p.status());
        m.put("targetAudience", p.targetAudience());
        m.put("currency", p.currency());
        m.put("nominalRateAnnual", p.nominalRateAnnual());
        m.put("moratoriumRateAnnual", p.moratoriumRateAnnual());
        m.put("minTerm", p.minTerm());
        m.put("maxTerm", p.maxTerm());
        m.put("defaultTerm", p.defaultTerm());
        m.put("minAmount", p.minAmount());
        m.put("maxAmount", p.maxAmount());
        m.put("defaultCreditLine", p.defaultCreditLine());
        m.put("minCreditLine", p.minCreditLine());
        m.put("maxCreditLine", p.maxCreditLine());
        m.put("amortizationType", p.amortizationType());
        m.put("minApprovalScore", p.minApprovalScore());
        m.put("defaultApprovalFlow", p.defaultApprovalFlow());
        m.put("openingFeeRate", p.openingFeeRate());
        m.put("prepaymentFeeRate", p.prepaymentFeeRate());
        // Lo que ve la app móvil = versión ACTIVE.
        m.put("activeInApp", "ACTIVE".equals(p.status()));
        return m;
    }

    /**
     * Detalle de producto para el modal: sus condiciones y el scoring que tiene asignado, con las
     * reglas y umbrales en lenguaje claro. El scoring se empareja por tipo: cada política aplica al
     * {@code productTypeIntent} igual al {@code productType} del producto (una por tipo de prospecto).
     */
    static Map<String, Object> productDetail(CreditProductResponse p, List<ScoringPolicyView> policies) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", product(p));

        // Condiciones económicas, agrupadas para la ficha.
        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("tasaAnual", p.nominalRateAnnual());
        cond.put("tasaMoratoriaAnual", p.moratoriumRateAnnual());
        cond.put("comisionApertura", p.openingFeeRate());
        cond.put("comisionPrepago", p.prepaymentFeeRate());
        cond.put("plazoMeses", rango(p.minTerm(), p.defaultTerm(), p.maxTerm()));
        cond.put("monto", rango(p.minAmount(), null, p.maxAmount()));
        cond.put("lineaCredito", rango(p.minCreditLine(), p.defaultCreditLine(), p.maxCreditLine()));
        cond.put("moneda", p.currency());
        cond.put("amortizacion", p.amortizationType());
        cond.put("audiencia", p.targetAudience());
        m.put("condiciones", cond);

        List<ScoringPolicyView> applicable = policies == null ? List.of() : policies.stream()
                .filter(pol -> pol.productTypeIntent() != null
                        && pol.productTypeIntent().equalsIgnoreCase(p.productType()))
                .toList();

        Map<String, Object> scoring = new LinkedHashMap<>();
        scoring.put("scoreMinimoAprobacion", p.minApprovalScore());
        scoring.put("flujoAprobacion", p.defaultApprovalFlow());
        scoring.put("tienePoliticaAsignada", !applicable.isEmpty());

        // Para CADA tipo de prospecto, la política que gobierna a este producto. Los tipos salen de
        // la elegibilidad del producto unida a los que ya tienen política: así se ve tanto la que
        // aplica como el hueco (prospecto elegible SIN política asignada).
        Set<String> prospectTypes = new TreeSet<>();
        if (p.eligiblePartyTypes() != null) prospectTypes.addAll(p.eligiblePartyTypes());
        applicable.forEach(pol -> { if (pol.prospectType() != null) prospectTypes.add(pol.prospectType()); });

        List<Map<String, Object>> porTipo = prospectTypes.stream().map(tipo -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tipoProspecto", tipo);
            var governing = applicable.stream()
                    .filter(pol -> tipo.equalsIgnoreCase(pol.prospectType()))
                    .findFirst().orElse(null);
            row.put("gobernadoPor", governing == null ? null : scoringPolicy(governing));
            row.put("sinPolitica", governing == null);
            return row;
        }).toList();
        scoring.put("porTipoProspecto", porTipo);

        // Lista plana (compatibilidad); el desglose por tipo de arriba es la vista principal.
        scoring.put("politicas", applicable.stream().map(BackofficeViews::scoringPolicy).toList());
        m.put("scoring", scoring);
        return m;
    }

    /** Reutilizado por el listado de políticas: es la misma vista, no una parecida. */
    static Map<String, Object> policy(ScoringPolicyView pol) { return scoringPolicy(pol); }

    private static Map<String, Object> scoringPolicy(ScoringPolicyView pol) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyId", pol.policyId() == null ? null : pol.policyId().toString());
        m.put("nombre", pol.name());
        m.put("descripcion", pol.description());
        m.put("tipoProspecto", pol.prospectType());
        m.put("tipoProducto", pol.productTypeIntent());
        m.put("version", pol.version());
        m.put("activa", pol.active());
        m.put("reglas", pol.rules() == null ? List.of()
                : pol.rules().stream().map(BackofficeViews::scoringRule).toList());
        m.put("umbrales", pol.thresholds() == null ? List.of()
                : pol.thresholds().stream()
                    .sorted((a, b) -> Integer.compare(b.minScore(), a.minScore()))
                    .map(BackofficeViews::scoringThreshold).toList());
        return m;
    }

    private static Map<String, Object> scoringRule(RuleView r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tipo", r.ruleType());
        m.put("tipoCredito", r.creditType());
        m.put("operador", r.operator());
        m.put("valor", r.thresholdValue());
        m.put("puntos", r.scoreContribution());
        m.put("descalifica", r.disqualifying());
        m.put("periodoMeses", r.periodMonths());
        m.put("descripcion", r.description());
        m.put("legible", ruleText(r));   // la regla en una frase, para el modal
        return m;
    }

    private static Map<String, Object> scoringThreshold(ThresholdView t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nivelRiesgo", t.riskLevel());
        m.put("scoreMinimo", t.minScore());
        m.put("decision", t.decision());
        m.put("legible", "Riesgo " + t.riskLevel() + ": score ≥ " + t.minScore()
                + " → " + decisionText(t.decision()));
        return m;
    }

    /** La regla de scoring en una frase legible: "FICO ≥ 650 → +40 pts" / "Mora > 90 → descalifica". */
    static String ruleText(RuleView r) {
        // El sujeto de la frase: qué se está mirando. Si falta un caso, la regla se lee con su
        // nombre interno —«WORST_ARREARS_BALANCE ≤ 5000»— y deja de ser explicable a un cliente,
        // que es justo para lo que existe esta traducción.
        String sujeto = switch (r.ruleType()) {
            case "MORA_CHECK"     -> "Días de mora";
            case "FICO_THRESHOLD" -> "Score de buró";
            case "CREDIT_COUNT"   -> "Número de créditos" + (r.creditType() != null ? " (" + r.creditType() + ")" : "");
            case "BALANCE_CHECK"  -> "Saldo vencido hoy";
            case "INQUIRY_COUNT"  -> "Consultas al buró";
            case "WORST_ARREARS_BALANCE"  -> "Saldo en su peor mora";
            case "OVERDUE_ACCOUNTS_COUNT" -> "Cuentas en mora";
            case "CURRENT_ACCOUNTS_COUNT" -> "Cuentas al corriente";
            case "OVERDUE_PAYMENTS_COUNT" -> "Pagos vencidos";
            case "ARREARS_RECENCY_MONTHS" -> "Meses desde su peor atraso";
            case "PREVENTION_KEY_COUNT"   -> "Créditos con clave de prevención";
            case "TOTAL_DEBT"             -> "Deuda total vigente";
            case "CREDIT_UTILIZATION"     -> "Uso de sus líneas (%)";
            case "MONTHLY_PAYMENT_LOAD"   -> "Pago mensual comprometido";
            case "DEBT_TO_INCOME"         -> "Carga sobre su ingreso (%)";
            case "CREDIT_HISTORY_MONTHS"  -> "Antigüedad de su historial (meses)";
            case "AGE_YEARS"              -> "Edad";
            case "MONTHLY_INCOME"         -> "Ingreso mensual";
            case "EMPLOYMENT_MONTHS"      -> "Antigüedad en el empleo (meses)";
            case "DEPENDENTS_COUNT"       -> "Dependientes económicos";
            default -> r.ruleType();
        };
        String op = switch (r.operator()) {
            case "GT" -> ">"; case "GTE" -> "≥"; case "LT" -> "<"; case "LTE" -> "≤"; case "EQ" -> "="; default -> r.operator();
        };
        String valor = trimNumber(r.thresholdValue());
        String periodo = r.periodMonths() != null ? " en " + r.periodMonths() + " meses" : "";
        String efecto = r.disqualifying() ? "descalifica"
                : (r.scoreContribution() >= 0 ? "+" : "") + r.scoreContribution() + " pts";
        return sujeto + " " + op + " " + valor + periodo + " → " + efecto;
    }

    private static String decisionText(String decision) {
        if (decision == null) return "";
        return switch (decision) {
            case "APPROVE", "APPROVED" -> "aprobar";
            case "REVIEW", "MANUAL_REVIEW" -> "revisión manual";
            case "REJECT", "REJECTED" -> "rechazar";
            default -> decision.toLowerCase();
        };
    }

    private static String trimNumber(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static Map<String, Object> rango(Object min, Object def, Object max) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("min", min);
        if (def != null) r.put("default", def);
        r.put("max", max);
        return r;
    }

    // ── Cálculos de riesgo compartidos (IFRS-9) ───────────────────────────────

    /** El adeudo íntegro. El IVA cuenta: se le cobra al cliente aunque no sea ingreso propio. */
    static BigDecimal totalDebt(CreditAccountResponse a) {
        return nz(a.principalBalance()).add(nz(a.accruedInterestBalance()))
                .add(nz(a.penaltyBalance())).add(nz(a.ivaBalance()));
    }

    static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Tramo IFRS-9 por días de atraso: al corriente ≤30, SICR ≤90, deteriorado &gt;90. */
    static String ifrs9Stage(int daysDelinquent) {
        if (daysDelinquent > 90) return "STAGE_3";
        if (daysDelinquent > 30) return "STAGE_2";
        return "STAGE_1";
    }

    /** Pérdida esperada por tramo. Provisional hasta que riesgo publique la suya. */
    static BigDecimal expectedLossRate(String stage) {
        return switch (stage) {
            case "STAGE_3" -> new BigDecimal("0.60");
            case "STAGE_2" -> new BigDecimal("0.20");
            default        -> new BigDecimal("0.01");
        };
    }

    static BigDecimal round2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
