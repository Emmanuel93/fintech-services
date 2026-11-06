package com.fintech.commission.application.service;

import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries.LotFigures;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries.OpenGroup;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries.OverReversal;
import com.fintech.commission.application.port.out.CommissionSelfCheckQueries.Run;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.LiquidationBatchStatus;
import com.fintech.commission.domain.LotTaxes;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fintech.shared.selfcheck.SelfCheckText.money;
import static com.fintech.shared.selfcheck.SelfCheckText.sample;

/**
 * The commission service checks itself, on its own data: every lot adds up to its records and to its net, withholds
 * what the beneficiary's regime asks, no reversal takes back more than its accrual, and whatever the last run left open
 * has a reason to be open. Two more checks inform: lots sent days ago with no outcome, and lots waiting to be retried.
 *
 * <p>Before, all of this was recomputed from outside by the chain's conciliation, reading this service's tables. The
 * system now says it of itself, and the outside reading stays as a second opinion.
 */
@Service
@Transactional(readOnly = true)
public class CommissionSelfCheckService {

    public static final String SERVICE = "commission-service";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");
    /** SPEI settles in minutes; a lot still SENT two days after it opened lost its outcome somewhere. */
    private static final Duration NO_OUTCOME_AFTER = Duration.ofDays(2);

    private final CommissionSelfCheckQueries queries;
    private final BeneficiaryProfileRepository profiles;
    private final CommissionProperties properties;

    public CommissionSelfCheckService(CommissionSelfCheckQueries queries, BeneficiaryProfileRepository profiles,
                                      CommissionProperties properties) {
        this.queries = queries;
        this.profiles = profiles;
        this.properties = properties;
    }

    /** The checks of a period's lots, or of everything with none. */
    public SelfCheckReport check(String period) {
        Instant now = BusinessClock.current().now();
        List<LotFigures> lots = queries.lots(period);
        Optional<Run> run = queries.lastRun();
        List<OpenGroup> open = run.map(r -> queries.openAsOf(r.period(), r.runAt())).orElse(List.of());
        Map<UUID, BeneficiaryProfile> profilesById = profilesOf(lots, open);
        List<SelfCheck> checks = new ArrayList<>();
        checks.add(lotsAddUpToTheirRecords(lots));
        checks.add(lotsAddUpToTheirNet(lots));
        checks.add(lotsWithholdWhatTheRegimeAsks(lots, profilesById));
        checks.add(noReversalTakesBackMore());
        checks.add(whatTheRunLeftOpenHasAReason(run, open, profilesById));
        checks.add(lotsWithoutOutcome(lots, now));
        checks.add(lotsWaitingForRetry(lots));
        return SelfCheckReport.of(SERVICE, period, now, checks);
    }

    private SelfCheck lotsAddUpToTheirRecords(List<LotFigures> lots) {
        List<String> off = new ArrayList<>();
        BigDecimal diff = BigDecimal.ZERO;
        for (LotFigures l : lots) {
            BigDecimal d = orZero(l.subtotal()).subtract(l.recordsSubtotal()).abs()
                    .add(orZero(l.iva()).subtract(l.recordsIva()).abs());
            if (d.compareTo(TOLERANCE) > 0 || l.recordsCount() != l.recordsFound()) {
                diff = diff.add(d);
                off.add("lote " + shortId(l) + " " + l.period() + ": comisión " + money(l.subtotal()) + " / registros "
                        + money(l.recordsSubtotal()) + ", IVA " + money(l.iva()) + " / " + money(l.recordsIva()) + ", "
                        + l.recordsCount() + " / " + l.recordsFound() + " registros");
            }
        }
        BigDecimal subtotal = sum(lots, LotFigures::subtotal);
        return SelfCheck.mustHold("LOTES_SUMAN_SUS_REGISTROS", "Cada lote suma sus registros de comisión", off.size(), diff,
                off.isEmpty()
                        ? lots.size() + " lotes: cada uno suma sus registros (comisión " + money(subtotal) + ", IVA "
                                + money(sum(lots, LotFigures::iva)) + ")"
                        : off.size() + " de " + lots.size() + " lotes no suman sus registros (" + sample(off) + ")");
    }

    private SelfCheck lotsAddUpToTheirNet(List<LotFigures> lots) {
        List<String> off = new ArrayList<>();
        BigDecimal diff = BigDecimal.ZERO;
        int before = 0;
        for (LotFigures l : lots) {
            if (l.subtotal() == null || l.net() == null) {
                before++;   // a lot opened before taxes: it has no breakdown to check
                continue;
            }
            BigDecimal gross = l.subtotal().add(l.iva());
            BigDecimal net = gross.subtract(l.isrWithheld()).subtract(l.ivaWithheld());
            BigDecimal d = l.total().subtract(gross).abs().add(l.net().subtract(net).abs());
            if (d.compareTo(TOLERANCE) > 0) {
                diff = diff.add(d);
                off.add("lote " + shortId(l) + ": total " + money(l.total()) + " por " + money(gross) + ", neto "
                        + money(l.net()) + " por " + money(net));
            }
        }
        String extra = before == 0 ? "" : "; " + before + " anteriores a los impuestos sin desglose";
        return SelfCheck.mustHold("NETO_DE_CADA_LOTE", "Neto de cada lote: comisión + IVA − retenciones", off.size(), diff,
                off.isEmpty()
                        ? (lots.size() - before) + " lotes con su neto en orden (neto " + money(sum(lots, LotFigures::net))
                                + ")" + extra
                        : off.size() + " lotes con el neto mal armado (" + sample(off) + ")" + extra);
    }

    /** The profiles of every beneficiary the checks look at, read once. */
    private Map<UUID, BeneficiaryProfile> profilesOf(List<LotFigures> lots, List<OpenGroup> open) {
        Set<UUID> ids = new HashSet<>();
        lots.forEach(l -> ids.add(l.beneficiaryPartyId()));
        open.forEach(g -> ids.add(g.beneficiaryPartyId()));
        Map<UUID, BeneficiaryProfile> byId = new HashMap<>();
        profiles.findAllById(ids).forEach(p -> byId.put(p.getPartyId(), p));
        return byId;
    }

    private SelfCheck lotsWithholdWhatTheRegimeAsks(List<LotFigures> lots, Map<UUID, BeneficiaryProfile> profilesById) {
        List<String> off = new ArrayList<>();
        BigDecimal diff = BigDecimal.ZERO;
        int checked = 0;
        for (LotFigures l : lots) {
            if (l.subtotal() == null) continue;
            Optional<BeneficiaryProfile> profile = Optional.ofNullable(profilesById.get(l.beneficiaryPartyId()));
            if (profile.isEmpty()) {
                off.add("lote " + shortId(l) + " sin perfil fiscal de su beneficiaria");
                continue;
            }
            checked++;
            LotTaxes expected = LotTaxes.of(l.subtotal(), l.iva(), profile.get(), properties.taxRules());
            BigDecimal d = l.isrWithheld().subtract(expected.isrWithheld()).abs()
                    .add(l.ivaWithheld().subtract(expected.ivaWithheld()).abs());
            if (d.compareTo(TOLERANCE) > 0) {
                diff = diff.add(d);
                off.add("lote " + shortId(l) + " (régimen " + profile.get().getTaxRegime() + "): ISR "
                        + money(l.isrWithheld()) + " por " + money(expected.isrWithheld()) + ", IVA retenido "
                        + money(l.ivaWithheld()) + " por " + money(expected.ivaWithheld()));
            }
        }
        return SelfCheck.mustHold("RETENCIONES_POR_REGIMEN", "Retenciones de cada lote según el régimen de la beneficiaria",
                off.size(), diff,
                off.isEmpty()
                        ? checked + " lotes retienen lo que pide el régimen vigente (ISR " + money(sum(lots,
                                LotFigures::isrWithheld)) + ", IVA " + money(sum(lots, LotFigures::ivaWithheld)) + ")"
                        : off.size() + " lotes no retienen lo que pide el régimen vigente (" + sample(off) + ")");
    }

    private SelfCheck noReversalTakesBackMore() {
        List<OverReversal> over = queries.overReversals();
        BigDecimal excess = over.stream()
                .map(o -> o.amount().add(o.reversed()).negate().max(BigDecimal.ZERO)
                        .add(o.iva().add(o.ivaReversed()).negate().max(BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("REVERSAS_SIN_EXCESO", "Ninguna reversa toma más de lo que se devengó", over.size(),
                excess,
                over.isEmpty()
                        ? "Cada reversa cabe en su devengo: nada se revirtió dos veces"
                        : over.size() + " devengos revertidos de más por " + money(excess) + " ("
                                + sample(over.stream().map(o -> o.commissionId().toString().substring(0, 8) + ": "
                                        + money(o.amount()) + " revertido " + money(o.reversed().negate())).toList()) + ")");
    }

    /**
     * What the last run left open, it left for a reason: below the minimum, a beneficiary that cannot be paid yet, or a
     * lot of that period already open for the same beneficiary and product. Anything else is a payable sum it skipped.
     */
    private SelfCheck whatTheRunLeftOpenHasAReason(Optional<Run> run, List<OpenGroup> open,
                                                   Map<UUID, BeneficiaryProfile> profilesById) {
        if (run.isEmpty()) {
            return SelfCheck.mustHold("ARRASTRE_EXPLICADO", "Lo que la corrida dejó sin lote tiene razón", 0,
                    BigDecimal.ZERO, "Sin corridas de liquidación todavía");
        }
        String period = run.get().period();
        Set<String> lotted = queries.lots(period).stream()
                .map(l -> l.beneficiaryPartyId() + "|" + Objects.toString(l.productType(), ""))
                .collect(Collectors.toSet());
        BigDecimal minimum = properties.getMinLiquidationAmount();
        List<String> off = new ArrayList<>();
        BigDecimal skipped = BigDecimal.ZERO;
        int below = 0, waiting = 0, next = 0;
        for (OpenGroup g : open) {
            if (g.subtotal().compareTo(minimum) < 0) {
                below++;
            } else if (!Optional.ofNullable(profilesById.get(g.beneficiaryPartyId())).map(BeneficiaryProfile::isPayable)
                    .orElse(false)) {
                waiting++;
            } else if (lotted.contains(g.beneficiaryPartyId() + "|" + Objects.toString(g.productType(), ""))) {
                next++;
            } else {
                skipped = skipped.add(g.subtotal());
                off.add(g.beneficiaryPartyId().toString().substring(0, 8) + " " + g.productType() + ": "
                        + money(g.subtotal()) + " en " + g.records() + " registros");
            }
        }
        return SelfCheck.mustHold("ARRASTRE_EXPLICADO", "Lo que la corrida dejó sin lote tiene razón", off.size(), skipped,
                off.isEmpty()
                        ? "Corrida de " + period + ": " + open.size() + " grupos abiertos (" + below + " bajo el mínimo de "
                                + money(minimum) + ", " + waiting + " esperan datos de pago, " + next
                                + " con lote del período ya abierto)"
                        : off.size() + " grupos pagables que la corrida de " + period + " no tomó (" + sample(off) + ")");
    }

    private SelfCheck lotsWithoutOutcome(List<LotFigures> lots, Instant now) {
        Instant limit = now.minus(NO_OUTCOME_AFTER);
        List<LotFigures> stale = lots.stream()
                .filter(l -> LiquidationBatchStatus.SENT.name().equals(l.status()) && l.createdAt().isBefore(limit))
                .toList();
        return SelfCheck.informs("LOTES_SIN_DESENLACE", "Lotes enviados hace más de dos días sin desenlace", stale.size(),
                sum(stale, LotFigures::net),
                stale.isEmpty()
                        ? "Ningún lote enviado espera su desenlace desde hace días"
                        : stale.size() + " lotes enviados sin liquidar ni rechazar (" + sample(stale.stream()
                                .map(l -> "lote " + shortId(l) + " " + l.period() + " neto " + money(l.net())).toList())
                                + "): revisar su orden en tesorería");
    }

    private SelfCheck lotsWaitingForRetry(List<LotFigures> lots) {
        List<LotFigures> retry = lots.stream()
                .filter(l -> LiquidationBatchStatus.PENDING.name().equals(l.status()) && l.payoutAttempts() > 0)
                .toList();
        return SelfCheck.informs("LOTES_POR_REINTENTAR", "Lotes cuyo pago falló y esperan la siguiente corrida",
                retry.size(), sum(retry, LotFigures::net),
                retry.isEmpty()
                        ? "Ningún lote espera un nuevo intento de pago"
                        : retry.size() + " lotes esperan un nuevo intento (" + sample(retry.stream()
                                .map(l -> "lote " + shortId(l) + " " + l.payoutAttempts() + " intentos: "
                                        + Objects.toString(l.failureReason(), "sin motivo")).toList()) + ")");
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).map(CommissionSelfCheckService::orZero).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String shortId(LotFigures l) {
        return l.batchId().toString().substring(0, 8);
    }

}
