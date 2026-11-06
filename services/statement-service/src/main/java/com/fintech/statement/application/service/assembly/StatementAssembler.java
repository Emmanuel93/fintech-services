package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.Movement;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.fintech.statement.application.service.assembly.Formats.date;
import static com.fintech.statement.application.service.assembly.Formats.money;
import static com.fintech.statement.application.service.assembly.Formats.nz;
import static com.fintech.statement.application.service.assembly.Formats.rate;

/**
 * Fills a product's template. There is one per family ({@link StatementLayout}); what every statement of a SOFOM ENR
 * carries is here (CONDUSEF DT-ENR 2015, arts. 33-37; LTOSF 13):
 * <ul>
 *   <li>issuer: company name with "SOFOM, E.N.R.", RFC, address and contact (35-I);</li>
 *   <li>holder, contract, period, cutoff, due date and the non-business-day notice (35-II/III, LTOSF 18 Bis 4);</li>
 *   <li>every movement of the period, dated and described (35-IV), and the fees with their total (35-V/VI);</li>
 *   <li>the cost box: CAT without IVA, annual rates, interest of the period and its basis (36-IV/IX, 37);</li>
 *   <li>how to pay, the UNE/CONDUSEF notice with the 90-day window (35-VIII, LTOSF 23), the warnings that apply to
 *       the product (36-X, 6-IV), the SOFOM ENR legend (LGOAAC 87-J) and the fiscal notice.</li>
 * </ul>
 * Tables go in one data source, row by row with their section: the product's detail, the plans settled in the period
 * ({@code LIQ}, only when there are), then {@code MOV}, then {@code COM}. A section with nothing to list carries one
 * row saying so.
 */
public abstract class StatementAssembler {

    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("DISPOSITION_SELF_USE", "Disposición"),
            Map.entry("DISPOSITION_THIRD_PARTY_CREDIT", "Colocación a cliente"),
            Map.entry("DISPOSITION_PAYROLL", "Disposición de nómina"),
            Map.entry("PAYMENT_APPLIED", "Pago recibido"),
            Map.entry("PAYMENT_RETURNED", "Pago devuelto"),
            Map.entry("CHARGE_OPENING_FEE", "Comisión por apertura"),
            Map.entry("CHARGE_ADMIN_FEE", "Comisión por administración"),
            Map.entry("CHARGE_PREPAYMENT_FEE", "Comisión por pago anticipado"),
            Map.entry("CHARGE_INSURANCE_PREMIUM", "Seguro"),
            Map.entry("CHARGE_REVERSED", "Reverso de cargo"),
            Map.entry("CHARGE_WAIVED", "Condonación"),
            Map.entry("WRITE_OFF", "Castigo de saldo"),
            Map.entry("ACTIVATION_DISBURSEMENT", "Disposición del crédito"),
            Map.entry("AGREEMENT_RESTRUCTURE", "Reestructura del crédito"),
            Map.entry("AGREEMENT_QUITA_PARCIAL", "Quita por convenio"),
            Map.entry("DISPOSITION_RETURNED", "Disposición devuelta"),
            // D63: the money never left; the principal it had charged was given back.
            Map.entry("DISPOSITION_FAILED", "Disposición no realizada"));

    /** Charges that are not fees: interest, its IVA, insurance, and undoing a charge. */
    private static final Set<String> NOT_FEES = Set.of("CHARGE_ORDINARY_INTEREST", "CHARGE_MORATORIUM_INTEREST",
            "CHARGE_IVA", "CHARGE_INSURANCE_PREMIUM", "CHARGE_REVERSED", "CHARGE_WAIVED");

    /** Products whose holder is a business: the consumer-credit legends do not apply to them. */
    private static final Set<String> BUSINESS_PRODUCTS = Set.of("SME_LOAN", "BUSINESS_REVOLVING_LINE", "DISTRIBUTOR_LINE");

    public abstract StatementLayout layout();

    public abstract Assembled assemble(AssemblyContext ctx);

    /** Issuer, holder, period, notices and legends: the same in every template. */
    protected DocumentModel common(AssemblyContext ctx) {
        CutoffFacts f = ctx.facts();
        SourceView v = ctx.view();
        StatementProperties.Issuer issuer = ctx.properties().getIssuer();
        String contract = v.account().contractNumber();
        boolean distributor = "DISTRIBUTOR".equalsIgnoreCase(f.effectiveHolderKind());

        return new DocumentModel()
                .put("ISSUER_NAME", issuer.getName())
                .put("ISSUER_LEGEND", issuer.getLegend())
                .put("ISSUER_LEGAL_NAME", issuer.getLegalName())
                .put("ISSUER_DETAILS", "RFC " + issuer.getRfc() + " · " + issuer.getAddress())
                .put("ISSUER_CONTACT", "Atención a clientes: " + issuer.getPhone() + " · " + issuer.getUneEmail()
                        + " · " + issuer.getWebsite())
                .put("HOLDER_KIND_LABEL", distributor ? "Distribuidora" : "Cliente")
                .put("HOLDER_NAME", ctx.holder() != null ? ctx.holder().displayName() : "Titular de la cuenta")
                .put("HOLDER_RFC", ctx.holder() != null ? ctx.holder().rfc() : null)
                .put("CONTRACT_NUMBER", contract)
                .put("STATEMENT_FOLIO", folio(ctx))
                .put("PERIOD", Formats.period(f.effectivePeriodStart(), f.cutoffDate()))
                .put("CUTOFF_DATE", date(f.cutoffDate()))
                .put("DUE_DATE", date(f.paymentDueDate()))
                .put("DUE_NOTICE", "Si la fecha límite es día inhábil, puedes pagar el siguiente día hábil sin cargo.")
                .put("CYCLE", String.valueOf(f.cycleNumber()))
                .put("PAYMENT_INSTRUCTIONS", paymentInstructions(ctx, contract))
                .put("CLARIFICATIONS_NOTICE", "Tienes 90 días naturales contados a partir de la fecha de corte o de la "
                        + "operación para presentar una aclaración o queja sobre los movimientos de este estado de cuenta. "
                        + "Al presentarla te damos un número de folio, que aparecerá con el cargo objetado en tu siguiente "
                        + "estado de cuenta.")
                .put("UNE_NOTICE", issuer.getLegalName() + " recibe las consultas, reclamaciones o aclaraciones en su "
                        + "Unidad Especializada de Atención a Usuarios, ubicada en " + issuer.getUneAddress()
                        + ", y por correo electrónico " + issuer.getUneEmail() + " o teléfono " + issuer.getUnePhone()
                        + ", así como en cualquiera de sus sucursales u oficinas. En el caso de no obtener una respuesta "
                        + "satisfactoria, podrá acudir a la Comisión Nacional para la Protección y Defensa de los "
                        + "Usuarios de Servicios Financieros (CONDUSEF): " + issuer.getCondusefWebsite() + ", teléfono "
                        + issuer.getCondusefPhone() + ".")
                .put("WARNINGS", warnings(f, v))
                .put("REGULATORY_LEGEND", issuer.getLegalName() + " para su constitución y operación con tal carácter no "
                        + "requiere de autorización de la Secretaría de Hacienda y Crédito Público y está sujeta a la "
                        + "supervisión de la Comisión Nacional Bancaria y de Valores únicamente para efectos de lo "
                        + "dispuesto por el artículo 56 de la Ley General de Organizaciones y Actividades Auxiliares del "
                        + "Crédito.")
                .put("FISCAL_NOTICE", "Este estado de cuenta no es un comprobante fiscal: el CFDI de los intereses y "
                        + "comisiones del periodo se emite por separado. El IVA se traslada sobre los intereses y las "
                        + "comisiones conforme a la Ley del IVA.")
                .put("LEGAL_NOTICE", issuer.getLegalNotice())
                .put("GENERATED_AT", Formats.stamp(ctx.generatedAt(), ctx.zone()));
    }

    /**
     * The warnings that apply (DT-ENR 6-IV, through 36-X): consumer credit carries the excessive-debt and
     * credit-history ones, revolving consumer credit the minimum-payment one, a group loan the joint-liability one.
     * Rates are fixed in every product, so the variable-rate one does not apply.
     */
    private static String warnings(CutoffFacts f, SourceView v) {
        String product = v.account().productType() != null ? v.account().productType() : f.productType();
        List<String> w = new ArrayList<>();
        w.add("Incumplir tus obligaciones te puede generar comisiones e intereses moratorios.");
        if (!BUSINESS_PRODUCTS.contains(product)) {
            w.add("Contratar créditos que excedan tu capacidad de pago afecta tu historial crediticio.");
        }
        if ("CREDIT_CARD".equals(product) || "REVOLVING_LINE".equals(product)) {
            w.add("Realizar sólo el pago mínimo aumenta el tiempo de pago y el costo de la deuda.");
        }
        if ("GROUP_LOAN".equals(product)) {
            w.add("El avalista, obligado solidario o coacreditado responderá como obligado principal por el total del "
                  + "pago frente a la Entidad Financiera.");
        }
        return String.join(" ", w);
    }

    /** Readable folio: the cutoff date and the start of the id, to quote it over the phone. */
    protected static String folio(AssemblyContext ctx) {
        return "EC-" + ctx.facts().cutoffDate().toString().replace("-", "") + "-"
               + ctx.statementId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String paymentInstructions(AssemblyContext ctx, String contract) {
        var issuer = ctx.properties().getIssuer();
        String clabe = ctx.view().account().clabe();
        Long reference = ctx.view().account().paymentReference();
        String how = clabe != null && !clabe.isBlank()
                ? "Paga por SPEI a tu CLABE de cobro " + clabe + " a nombre de " + issuer.getLegalName()
                  + (issuer.getLegalName().endsWith(".") ? "" : ".")
                : "Paga desde la app o en los canales de " + issuer.getName() + ".";
        String which = reference != null
                ? " Escribe " + reference + " en «referencia numérica» para que el pago se aplique a este crédito ("
                  + contract + "); sin ella se aplica a tu crédito más antiguo."
                : " Usa tu número de contrato " + contract + " como referencia.";
        return how + which + " Si pagas después del " + date(ctx.facts().paymentDueDate())
               + " se generan intereses moratorios.";
    }

    // ── Movements and fees (DT-ENR 35-IV, V and VI) ──────────────────────────────────────────────────────────────

    /**
     * Every movement of the period, dated and described; the daily accrual goes summed, one row per concept (thirty
     * rows of daily interest say nothing to anyone).
     */
    protected static void movements(DocumentModel m, AssemblyContext ctx) {
        CutoffFacts f = ctx.facts();
        var t = ctx.view().totals();
        List<Movement> listed = ctx.view().movements() == null ? List.of() : ctx.view().movements();
        int before = m.rows().size();
        for (Movement mv : listed) {
            boolean credit = nz(mv.amount()).signum() < 0 || mv.type().startsWith("PAYMENT_APPLIED")
                             || "WRITE_OFF".equals(mv.type()) || "CHARGE_REVERSED".equals(mv.type())
                             || "CHARGE_WAIVED".equals(mv.type());
            String amount = money(nz(mv.amount()).abs());
            m.row("section", "MOV", "c1", date(mv.appliedAt(), ctx.zone()), "c2", concept(mv.type()),
                  "c3", credit ? "" : amount, "c4", credit ? amount : "");
        }
        accrual(m, f, "Intereses ordinarios del periodo", t.ordinaryInterest());
        accrual(m, f, "IVA de intereses del periodo", t.iva());
        accrual(m, f, "Intereses moratorios del periodo", t.moratoriumInterest());
        if (m.rows().size() == before) {
            m.row("section", "MOV", "emphasis", "EMPTY", "c1", "Sin movimientos en el periodo.");
        }
    }

    private static void accrual(DocumentModel m, CutoffFacts f, String concept, BigDecimal amount) {
        if (nz(amount).signum() == 0) return;
        m.row("section", "MOV", "c1", date(f.cutoffDate()), "c2", concept, "c3", money(amount), "c4", "");
    }

    /** The fees charged in the period, with their date and concept; returns their total (the box, always shown). */
    protected static BigDecimal fees(DocumentModel m, AssemblyContext ctx) {
        List<Movement> listed = ctx.view().movements() == null ? List.of() : ctx.view().movements();
        BigDecimal total = BigDecimal.ZERO;
        for (Movement mv : listed) {
            if (mv.type() == null || !mv.type().startsWith("CHARGE_") || NOT_FEES.contains(mv.type())) continue;
            m.row("section", "COM", "c1", date(mv.appliedAt(), ctx.zone()), "c2", concept(mv.type()),
                  "c3", money(nz(mv.amount()).abs()));
            total = total.add(nz(mv.amount()).abs());
        }
        if (total.signum() == 0) {
            m.row("section", "COM", "emphasis", "EMPTY", "c1", "No se cobraron comisiones en el periodo.");
        }
        m.put("COMMISSIONS_TOTAL", money(total));
        return total;
    }

    // ── The cost box (DT-ENR 36-IV/IX, 37; LTOSF 9) ──────────────────────────────────────────────────────────────

    /**
     * CAT without IVA, annual rates (fixed), interest of the period and the basis it is computed on.
     *
     * @param cat   as a fraction; the contracted one when the account carries it
     * @param basis how the interest is computed, in words
     */
    protected static void costs(DocumentModel m, AssemblyContext ctx, BigDecimal cat, String catNote, String basis) {
        var a = ctx.view().account();
        var t = ctx.view().totals();
        m.slot("COST", 1, catNote == null ? "CAT (Costo Anual Total)" : "CAT estimado (" + catNote + ")", catText(cat))
         .slot("COST", 2, "Tasa de interés ordinaria anual fija", rate(a.nominalRate()))
         .slot("COST", 3, "Tasa de interés moratoria anual", rate(a.moratoriumRate()))
         .slot("COST", 4, "Intereses del periodo", money(nz(t.ordinaryInterest()).add(nz(t.moratoriumInterest()))))
         .slot("COST", 5, "IVA de intereses del periodo", money(t.iva()))
         .slot("COST", 6, "Base de cálculo de los intereses", basis);
    }

    /**
     * «153.9 % Sin IVA»: one value, one decimal, followed by "Sin IVA" (Banxico C21/2009, numeral 7). The CAT always
     * arrives as a fraction. It used to be guessed: a value up to 1 was read as a fraction and anything above as
     * already a percentage, so every CAT above 100 % — a distributor's placement at 95 % costs 153.9 % — printed as
     * «1.5 %».
     */
    protected static String catText(BigDecimal cat) {
        if (cat == null) return "No disponible";
        return cat.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + " % Sin IVA";
    }

    /** The CAT the client signed when the account carries it (stored in percent), else the plan's own. */
    protected static BigDecimal contractedCat(AssemblyContext ctx) {
        BigDecimal c = ctx.view().account().cat();
        return c == null ? null : c.movePointLeft(2);
    }

    /**
     * The plans settled in the period (a distributor's placements, a card's purchases at months), in their own table
     * after the live ones: who or what, when it was made, the amount, its term, the day it was settled and how. Live
     * and settled never share a table titled "vigentes". Nothing is added when none was settled.
     */
    protected static void settledRows(DocumentModel m, AssemblyContext ctx, List<PlacementLine> settled,
                                      Function<PlacementLine, String> name, String termUnit) {
        for (PlacementLine p : settled) {
            m.row("section", "LIQ",
                  "c1", name.apply(p),
                  "c2", date(p.placedAt(), ctx.zone()),
                  "c3", money(p.amount()),
                  "c4", p.totalInstallments() + termUnit,
                  "c5", p.settledOn() != null ? date(p.settledOn()) : "En el periodo",
                  "c6", p.settledOn() == null ? "—" : p.paidAhead() ? "Pago anticipado" : "Al vencimiento");
        }
    }

    /** The concept the client recognizes, not the internal code. */
    protected static String concept(String type) {
        return CONCEPTS.getOrDefault(type, type == null ? "Movimiento" : type.replace('_', ' ').toLowerCase(Locale.ROOT));
    }

    /** The previous balance: the previous statement's; in the first, what opened the period in the ledger. */
    protected static BigDecimal previousBalance(AssemblyContext ctx) {
        return ctx.previousBalance().orElseGet(() ->
                ctx.view().opening() == null ? BigDecimal.ZERO : nz(ctx.view().opening().total()));
    }

    protected static String plus(BigDecimal v)  { return money(nz(v)); }

    /** A credit: signed, unless it is zero — "−$0.00" tells nobody anything. */
    protected static String minus(BigDecimal v) {
        return nz(v).signum() == 0 ? money(BigDecimal.ZERO) : "−" + money(nz(v).abs());
    }
}
