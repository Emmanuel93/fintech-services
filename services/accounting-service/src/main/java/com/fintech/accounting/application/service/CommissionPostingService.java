package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.CommissionMoves.Accrual;
import com.fintech.accounting.application.CommissionMoves.LotPayout;
import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.CommissionFacts;
import com.fintech.accounting.application.port.out.CommissionFacts.Fact;
import com.fintech.accounting.application.port.out.CommissionFacts.Kind;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * El lado contable de las comisiones a distribuidores y promotores.
 *
 * <p>T6 sólo decide (devengar, revertir, armar el lote y pedir su SPEI); aquí se asienta el gasto, lo que se le debe a
 * la beneficiaria y, al pedirse el SPEI del lote, su paso al SPEI en vuelo. No pasa por {@code posting_rules} porque
 * viene de otra familia de tópicos y las cuentas no dependen de un {@code triggerEvent} configurable.
 *
 * <p><b>Impuestos.</b> La beneficiaria factura la comisión más IVA: se le debe el bruto (2120), el gasto es la comisión
 * (5104) y el IVA queda pendiente de acreditar (1141) hasta pagarse. Al pedirse el SPEI del lote, el bruto sale de 2120:
 * el neto al SPEI por liquidar (2103, partida {@code LOTE:<id>}, que el banco liquida con la orden), y lo retenido a una
 * persona física (ISR, dos tercios del IVA) a lo que se debe al SAT (2111, 2112); el IVA pagado se vuelve acreditable
 * (1140). Antes el lote salía de Bancos sin orden SPEI, y tesorería lo inventaba a partir del mismo hecho.
 *
 * <p>Each voucher names its item of 2120 ({@code COM:<beneficiary>:<product>}, or the lot) and records the fact as
 * commission reported it: the close reconciles 2120 against them, by beneficiary and product.
 *
 * <p>Not every commission is an expense when it accrues. One paid for originating the loan (the promoter's upfront
 * commission) is a transaction cost of the loan (IFRS 9 B5.4.8): it goes to 1205 and is amortized against the loan's
 * interest revenue over its life, like the opening fee with the opposite sign. A commission on what is collected
 * (the distributor's share of interest, the collector's bonus) pays for a service of the period: it is an expense.
 */
@Service
@Transactional
public class CommissionPostingService {

    /** Commissions that exist only because the loan was originated: transaction costs inside the effective rate. */
    private static final Set<String> ORIGINATION_COSTS = Set.of("ORIGINATION_FEE");

    static final String LOT_PAYOUT        = "COMMISSION_LOT_PAYOUT";
    static final String LOT_PAYOUT_FAILED = "COMMISSION_LOT_PAYOUT_FAILED";

    private final AccountBalanceShadowRepository shadowRepository;
    private final VoucherPostingService ledger;
    private final FeeDeferralService deferrals;
    private final CommissionFacts facts;
    private final AccountingProperties properties;

    public CommissionPostingService(AccountBalanceShadowRepository shadowRepository, VoucherPostingService ledger,
                                    FeeDeferralService deferrals, CommissionFacts facts,
                                    AccountingProperties properties) {
        this.shadowRepository = shadowRepository;
        this.ledger           = ledger;
        this.deferrals        = deferrals;
        this.facts            = facts;
        this.properties       = properties;
    }

    /**
     * A commission accrued. An origination commission is a cost of the loan, deferred over its life (1205); any other
     * is an expense of the period (5104) with its IVA pending (1141). Both are owed to the beneficiary (2120) until its
     * lot is paid.
     */
    public void onCommissionAccrued(Accrual a) {
        if (a.amount() == null || a.amount().signum() <= 0) return;
        if (a.commissionType() != null && ORIGINATION_COSTS.contains(a.commissionType()) && a.creditAccountId() != null) {
            accrueOriginationCost(a);
            return;
        }
        String key = a.commissionId().toString();
        if (ledger.isPosted(key)) return;
        String concept = a.isNetted() ? "Comisión de la distribuidora, neteada contra su estado de cuenta"
                : "Comisión devengada — distribuidor/promotor";
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair(AccountCodes.GASTO_COMISIONES, AccountCodes.COMISIONES_POR_PAGAR, a.amount(), concept));
        // A netted commission is a discount on what she delivers, not a payment made to her: it carries no IVA.
        if (a.ivaOrZero().signum() > 0) {
            pairs.add(new Pair(AccountCodes.IVA_PENDIENTE_ACREDITAR, AccountCodes.COMISIONES_POR_PAGAR, a.ivaOrZero(),
                    "IVA de la comisión, pendiente de acreditar"));
        }
        ledger.post(new VoucherRequest(key, "COMMISSION_ACCRUED", a.occurredAt(), a.creditAccountId(), null,
                        unitOf(a.creditAccountId()), concept, a.key()).withProduct(a.productType()), pairs);
        // What she is owed is settled by the portfolio's own netting move, never by a lot: no fact waits for one.
        if (!a.isNetted()) {
            fact(key, Kind.ACCRUED, a.beneficiaryPartyId(), a.productType(), null, null, a.gross(), a.occurredAt());
        }
    }

    /**
     * CM-05: a returned payment took back (part of) a commission. A reversed origination commission takes back what is
     * still deferred from 1205 and gives the interest revenue back what was already amortized.
     */
    public void onCommissionReversed(Accrual reversal) {
        if (reversal.amount() == null || reversal.amount().signum() <= 0) return;
        String key = reversal.commissionId() + ":reversed";
        if (ledger.isPosted(key)) return;
        String unit = unitOf(reversal.creditAccountId());
        UUID original = reversal.reversesCommissionId() != null ? reversal.reversesCommissionId() : reversal.commissionId();
        Optional<FeeDeferralService.Withdrawn> cost = deferrals.withdrawCost(costKey(original));
        if (cost.isPresent()) {
            String concept = "Comisión de originación revertida — pago devuelto";
            ledger.post(new VoucherRequest(key, "COMMISSION_REVERSED", reversal.occurredAt(), reversal.creditAccountId(),
                            null, unit, concept).withProduct(reversal.productType()),
                    List.of(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.COSTOS_ORIGINACION,
                                    cost.get().deferred(), concept + " (por amortizar)"),
                            new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.INGRESOS_TASA_EFECTIVA,
                                    cost.get().recognized(), concept + " (ya amortizada)")));
            return;
        }
        String concept = "Comisión revertida — pago devuelto";
        List<Pair> pairs = new ArrayList<>();
        pairs.add(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.GASTO_COMISIONES, reversal.amount(), concept));
        if (reversal.ivaOrZero().signum() > 0) {
            pairs.add(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.IVA_PENDIENTE_ACREDITAR,
                    reversal.ivaOrZero(), "IVA de la comisión revertida"));
        }
        ledger.post(new VoucherRequest(key, "COMMISSION_REVERSED", reversal.occurredAt(), reversal.creditAccountId(), null,
                        unit, concept, reversal.key()).withProduct(reversal.productType()), pairs);
        if (!reversal.isNetted()) {
            fact(key, Kind.REVERSED, reversal.beneficiaryPartyId(), reversal.productType(), null, null,
                    reversal.gross().negate(), reversal.occurredAt());
        }
    }

    /**
     * A lot's SPEI requested: its gross leaves what is owed to the beneficiary for the SPEI in flight (the net) and
     * what is withheld for the SAT; the IVA it pays becomes creditable. Without a loan: a lot pays many loans' commissions
     * at once, and attributing it to one would be inventing it. With its product: a lot is one beneficiary's, of one
     * product.
     */
    public void onLotPayoutRequested(LotPayout lot) {
        String key = lot.itemRef() + ":" + lot.attempt();
        if (lot.gross() == null || lot.gross().signum() <= 0 || ledger.isPosted(key)) return;
        String concept = "SPEI del lote de comisiones (intento " + lot.attempt() + ")";
        ledger.post(new VoucherRequest(key, LOT_PAYOUT, lot.occurredAt(), null, null, null, concept, lot.itemRef())
                        .withProduct(lot.productType()),
                List.of(new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.SPEI_POR_LIQUIDAR, lot.net(),
                                "Neto del lote al SPEI por liquidar"),
                        new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.ISR_RETENIDO_POR_ENTERAR, lot.isrWithheld(),
                                "ISR retenido a persona física"),
                        new Pair(AccountCodes.COMISIONES_POR_PAGAR, AccountCodes.IVA_RETENIDO_POR_ENTERAR, lot.ivaWithheld(),
                                "IVA retenido a persona física (dos terceras partes)"),
                        new Pair(AccountCodes.IVA_ACREDITABLE_PAGADO, AccountCodes.IVA_PENDIENTE_ACREDITAR, lot.ivaPaid(),
                                "IVA de la comisión pagado: acreditable")));
        fact(key, Kind.LOT_SENT, lot.beneficiaryPartyId(), lot.productType(), lot.batchId(), lot.attempt(),
                lot.gross().negate(), lot.occurredAt());
    }

    /** The lot's order failed or came back: the attempt's move is undone and the gross is owed to the beneficiary again. */
    public void onLotPayoutFailed(LotPayout lot) {
        String sent = lot.itemRef() + ":" + lot.attempt();
        String key = sent + ":failed";
        if (lot.gross() == null || lot.gross().signum() <= 0 || !ledger.isPosted(sent) || ledger.isPosted(key)) return;
        String concept = "SPEI del lote de comisiones no pagado (intento " + lot.attempt() + ")";
        ledger.post(new VoucherRequest(key, LOT_PAYOUT_FAILED, lot.occurredAt(), null, null, null, concept, lot.itemRef())
                        .withProduct(lot.productType()),
                List.of(new Pair(AccountCodes.SPEI_POR_LIQUIDAR, AccountCodes.COMISIONES_POR_PAGAR, lot.net(),
                                "Neto del lote: vuelve a deberse"),
                        new Pair(AccountCodes.ISR_RETENIDO_POR_ENTERAR, AccountCodes.COMISIONES_POR_PAGAR, lot.isrWithheld(),
                                "ISR retenido que no se pagó"),
                        new Pair(AccountCodes.IVA_RETENIDO_POR_ENTERAR, AccountCodes.COMISIONES_POR_PAGAR, lot.ivaWithheld(),
                                "IVA retenido que no se pagó"),
                        new Pair(AccountCodes.IVA_PENDIENTE_ACREDITAR, AccountCodes.IVA_ACREDITABLE_PAGADO, lot.ivaPaid(),
                                "IVA de la comisión no pagado: vuelve a pendiente")));
        fact(key, Kind.LOT_FAILED, lot.beneficiaryPartyId(), lot.productType(), lot.batchId(), lot.attempt(),
                lot.gross(), lot.occurredAt());
    }

    private void accrueOriginationCost(Accrual a) {
        String key = a.commissionId().toString();
        if (ledger.isPosted(key)) return;
        Optional<AccountBalanceShadow> shadow = shadowRepository.findById(a.creditAccountId());
        String concept = "Comisión de originación al promotor — costo de la transacción (tasa efectiva)";
        String unit = shadow.map(AccountBalanceShadow::getOrgUnitCode).orElse(null);
        ledger.post(new VoucherRequest(key, "COMMISSION_ACCRUED", a.occurredAt(), a.creditAccountId(), null, unit, concept)
                        .withProduct(a.productType()),
                List.of(new Pair(AccountCodes.COSTOS_ORIGINACION, AccountCodes.COMISIONES_POR_PAGAR, a.amount(), concept)));
        Integer months = shadow.map(AccountBalanceShadow::getTermMonths).orElse(null);
        UUID party = shadow.map(AccountBalanceShadow::getObligorPartyId).orElse(null);
        if (months != null && months > 1) {
            deferrals.deferCost(costKey(a.commissionId()), a.creditAccountId(), party, unit, a.amount(), months,
                    periodOf(a.occurredAt()));
            return;
        }
        // A loan whose life accounting does not know: the cost is recognized against its interest revenue at once.
        ledger.post(new VoucherRequest(costKey(a.commissionId()) + ":inmediata", FeeDeferralService.TRIGGER, a.occurredAt(),
                        a.creditAccountId(), party, unit, "Costo de originación reconocido al devengarse (sin plazo que lo difiera)")
                        .withProduct(a.productType()),
                List.of(new Pair(AccountCodes.INGRESOS_TASA_EFECTIVA, AccountCodes.COSTOS_ORIGINACION, a.amount(), concept)));
    }

    private void fact(String id, Kind kind, UUID beneficiary, String product, UUID batch, Integer attempt,
                      BigDecimal gross, Instant at) {
        if (beneficiary == null) return;
        facts.record(new Fact(id, kind, beneficiary, product, batch, attempt, gross, dayOf(at)));
    }

    private String unitOf(UUID creditAccountId) {
        return creditAccountId == null ? null : shadowRepository.findById(creditAccountId)
                .map(AccountBalanceShadow::getOrgUnitCode).orElse(null);
    }

    private static String costKey(UUID commissionId) {
        return "COMMISSION:" + commissionId;
    }

    private LocalDate dayOf(Instant occurredAt) {
        Instant at = occurredAt != null ? occurredAt : BusinessClock.current().now();
        return at.atZone(properties.zoneId()).toLocalDate();
    }

    private String periodOf(Instant occurredAt) {
        return YearMonth.from(dayOf(occurredAt)).toString().replace("-", "");
    }
}
