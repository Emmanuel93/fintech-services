package com.fintech.accounting.application.service;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.FundingBalanceRepository;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Books the funding treasury recorded: where the money the institution lends comes from.
 * <ul>
 *   <li>a capital contribution is equity (IAS 32): 1101 / 3101;</li>
 *   <li>a funding line is a financial liability at amortised cost (IFRS 9 4.2.1): a drawdown 1101 / 2201, its interest
 *       an expense of each day (5201 / 2202) paid from the bank (2202 / 1101), its principal repaid 2201 / 1101;</li>
 *   <li>the line's opening fee is a transaction cost (B5.4.8): deducted from the liability (2209 / 1101) and amortized
 *       into interest expense over the line's term (5201 / 2209) at each month's close.</li>
 * </ul>
 * Treasury decides and accounting books: the amounts, the interest and the balances come in the fact. The balances are
 * kept per source, and the month-end close reconciles 2201, 2202 and 3101 against them.
 */
@Service
public class FundingPostingService {

    private static final Logger log = LoggerFactory.getLogger(FundingPostingService.class);
    /**
     * The unit the funding is booked to: the institution's treasury, not a branch. Booked with no unit it was
     * counted under "Sin sucursal" with the loans whose branch was never sealed, and raised that warning.
     */
    public static final String CORPORATE_UNIT = "CORPORATIVO";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-MX"));

    private final VoucherPostingService ledger;
    private final FundingBalanceRepository balances;
    private final FeeDeferralService deferrals;
    private final AccountingProperties properties;

    public FundingPostingService(VoucherPostingService ledger, FundingBalanceRepository balances,
                                 FeeDeferralService deferrals, AccountingProperties properties) {
        this.ledger = ledger;
        this.balances = balances;
        this.deferrals = deferrals;
        this.properties = properties;
    }

    /** A movement of a funding source as treasury announced it. */
    public record FundingFact(String eventId, UUID movementId, UUID sourceId, String sourceKind, String counterpartyName,
                              String contractReference, String movementType, BigDecimal amount, LocalDate valueDate,
                              LocalDate accruedFrom, LocalDate accruedThrough, BigDecimal contributedAfter,
                              BigDecimal principalAfter, BigDecimal interestPayableAfter, String sourceStatus,
                              Integer termMonths, Instant occurredOn) {}

    @Transactional
    public void onMovement(FundingFact f) {
        if (f.amount() == null || f.amount().signum() <= 0 || f.movementType() == null) return;
        String who = f.counterpartyName() + (f.contractReference() == null ? "" : " (" + f.contractReference() + ")");
        switch (f.movementType()) {
            case "CONTRIBUTION" -> post(f, AccountCodes.BANCOS, AccountCodes.CAPITAL_SOCIAL,
                    "Aportación de capital social — " + who);
            case "DRAWDOWN" -> post(f, AccountCodes.BANCOS, AccountCodes.PRESTAMOS_FONDEO,
                    "Disposición de la línea de fondeo — " + who);
            case "OPENING_FEE" -> {
                post(f, AccountCodes.COSTOS_FONDEO_POR_AMORTIZAR, AccountCodes.BANCOS,
                        "Comisión de apertura de la línea de fondeo (costo de la transacción) — " + who);
                deferOpeningFee(f, who);
            }
            case "INTEREST_ACCRUAL" -> post(f, AccountCodes.GASTO_INTERESES_FONDEO, AccountCodes.INTERESES_FONDEO_POR_PAGAR,
                    "Intereses devengados de la línea de fondeo" + window(f) + " — " + who);
            case "INTEREST_PAYMENT" -> post(f, AccountCodes.INTERESES_FONDEO_POR_PAGAR, AccountCodes.BANCOS,
                    "Pago de intereses de la línea de fondeo — " + who);
            case "PRINCIPAL_REPAYMENT" -> post(f, AccountCodes.PRESTAMOS_FONDEO, AccountCodes.BANCOS,
                    "Amortización de capital de la línea de fondeo — " + who);
            default -> {
                log.warn("Movimiento de fondeo desconocido {} fuente={}: no se asienta", f.movementType(), f.sourceId());
                return;
            }
        }
        balances.record(f.sourceId(), f.sourceKind(), f.counterpartyName(), f.contractReference(), f.contributedAfter(),
                f.principalAfter(), f.interestPayableAfter(), f.accruedThrough(), f.sourceStatus(), f.movementId());
    }

    private void post(FundingFact f, String debit, String credit, String concept) {
        ledger.post(new VoucherRequest(f.eventId(), "FUNDING_" + f.movementType(), at(f), null, null, CORPORATE_UNIT, concept),
                List.of(new Pair(debit, credit, f.amount(), concept)));
    }

    /** Over the line's term from the month it was paid; a line without term is amortized at once. */
    private void deferOpeningFee(FundingFact f, String who) {
        Integer months = f.termMonths();
        if (months != null && months > 1) {
            deferrals.deferFunding(f.eventId(), f.sourceId(), CORPORATE_UNIT, f.amount(), months, periodOf(at(f)));
            return;
        }
        String concept = "Comisión de apertura de la línea de fondeo amortizada al pagarse — " + who;
        ledger.post(new VoucherRequest(f.eventId() + ":inmediata", FeeDeferralService.TRIGGER, at(f), null, null,
                CORPORATE_UNIT, concept), List.of(new Pair(AccountCodes.GASTO_INTERESES_FONDEO, AccountCodes.COSTOS_FONDEO_POR_AMORTIZAR,
                f.amount(), concept)));
    }

    private static String window(FundingFact f) {
        if (f.accruedFrom() == null || f.accruedThrough() == null) return "";
        return " del " + DAY.format(f.accruedFrom()) + " al " + DAY.format(f.accruedThrough());
    }

    private Instant at(FundingFact f) {
        if (f.occurredOn() != null) return f.occurredOn();
        return f.valueDate().atTime(12, 0).atZone(properties.zoneId()).toInstant();
    }

    private String periodOf(Instant at) {
        return YearMonth.from(at.atZone(properties.zoneId())).toString().replace("-", "");
    }
}
