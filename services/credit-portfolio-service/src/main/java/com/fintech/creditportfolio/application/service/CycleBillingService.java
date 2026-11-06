package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CardMinimumPayment;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import com.fintech.shared.time.BusinessClock;

/**
 * Convierte el corte del ciclo en <b>algo exigible con fecha</b>.
 *
 * <p>La regla, escrita:
 *
 * <pre>
 * exigible = Σ compras del ciclo  −  Σ compras diferidas  +  Σ cuotas de planes vigentes
 * </pre>
 *
 * <p><b>La resta sale sola.</b> Diferir una compra la convierte en {@code AMORTIZED} y le da
 * calendario propio; deja de ser {@code REVOLVING} y por tanto deja de sumar aquí. No hay que
 * restarla: ya no está. Y sus cuotas entran por el tercer término, como las de cualquier plan.
 *
 * <p><b>Sólo capital.</b> La cuota del ciclo lleva el importe de las compras y nada de interés: el
 * ordinario lo devenga {@code charges} día a día por su cuenta, y meterlo también aquí lo cobraría
 * dos veces. Además es lo que mantiene correcta la base del moratorio, que es capital vencido
 * (BK-19): si la cuota del ciclo llevara interés, la mora se cobraría sobre interés.
 *
 * <h2>What the statement asks for (2026-11-02)</h2>
 * <ul>
 *   <li><b>The plans' installments are billed by the statement.</b> Placements, deferred purchases and
 *       amortized purchases have their own installments with their own dates; a holder pays them with the
 *       statement, not on each date. Every pending installment due up to the payment due date is billed
 *       here, once, and from then on it is due on that date ({@link Installment#billIn}). On a fortnight, never
 *       one dated on the next cutoff, even when holidays move the due date onto it: that one is the next
 *       statement's ({@link BillingCycle#lastBillableDay}). Before, a
 *       distributor that paid every statement on time was charged moratorium for the days between each
 *       placement's installment date and the statement's due date.</li>
 *   <li><b>The cycle installment is the minimum payment</b>, never more than what is revolving (the balance
 *       minus what the plans still owe). It used to be the sum of the cycle's purchases: a holder who paid
 *       the minimum was left "overdue" for the rest and charged moratorium on it, and a purchase already
 *       paid off before the cutoff was billed anyway. Without a minimum (no policy) the old rule stays.</li>
 * </ul>
 */
@Service
public class CycleBillingService implements CloseCutoffCycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(CycleBillingService.class);

    private final CreditAccountRepository accounts;
    private final DispositionRepository dispositions;
    private final InstallmentRepository installments;
    private final BalanceEventRepository ledger;
    private final ProductConfigResolver configResolver;

    @Autowired
    public CycleBillingService(CreditAccountRepository accounts,
                               DispositionRepository dispositions,
                               InstallmentRepository installments,
                               BalanceEventRepository ledger,
                               ProductConfigResolver configResolver) {
        this.accounts       = accounts;
        this.dispositions   = dispositions;
        this.installments   = installments;
        this.ledger         = ledger;
        this.configResolver = configResolver;
    }

    /** Without the product's configuration: a statement bills up to its payment due date. */
    public CycleBillingService(CreditAccountRepository accounts, DispositionRepository dispositions,
                               InstallmentRepository installments, BalanceEventRepository ledger) {
        this(accounts, dispositions, installments, ledger, null);
    }

    /** Without the ledger (unit tests of the purchases rule): the period's charges are not added. */
    public CycleBillingService(CreditAccountRepository accounts, DispositionRepository dispositions,
                               InstallmentRepository installments) {
        this(accounts, dispositions, installments, null);
    }

    @Override
    @Transactional
    public void onCutoffClosed(CorteCerrado cutoff) {
        CreditAccount account = accounts.findById(cutoff.creditAccountId()).orElse(null);
        if (account == null) {
            log.warn("cutoff-closed sin cuenta conocida creditAccountId={} — ignorado",
                    cutoff.creditAccountId());
            return;
        }
        if (!account.isRevolving()) {
            // Un producto a plazo ya tiene su exigible en el plan de amortización. Fabricarle otro
            // aquí sería cobrarle dos veces la misma cuota.
            log.debug("Corte de cuenta a plazo {} — su exigible sale del plan", cutoff.creditAccountId());
            return;
        }

        // El ciclo se factura UNA vez. `uq_installment_number` es (schedule_id, número), y aquí el
        // número es el del ciclo: una segunda cuota del mismo ciclo choca contra la restricción y
        // tumba el procesamiento del corte entero.
        //
        // Pasa de verdad: el corte se reentrega —Kafka entrega al menos una vez— y entretanto puede
        // haber llegado tarde el evento de una compra con fecha anterior al corte. Esa compra
        // pertenece al ciclo SIGUIENTE, no a uno ya facturado y ya comunicado al cliente.
        if (alreadyBilled(cutoff)) {
            log.info("El ciclo {} de la cuenta {} ya estaba facturado — las compras rezagadas "
                            + "entran al corte siguiente",
                    cutoff.cycleNumber(), cutoff.creditAccountId());
            return;
        }

        List<Disposition> deLaCuenta = dispositions.findByCreditAccountId(cutoff.creditAccountId());
        List<Disposition> porFacturar = deLaCuenta.stream()
                .filter(Disposition::isDueAtCutoff)
                .filter(d -> !date(d).isAfter(cutoff.cutoffDate()))
                .toList();

        // 1) The plans' installments due up to the payment due date are billed by this statement; on a fortnight, not
        //    the ones dated on the next cutoff.
        List<Installment> planes = deLaCuenta.isEmpty() ? List.of()
                : installments.findByScheduleIds(deLaCuenta.stream().map(Disposition::getDispositionId).toList());
        LocalDate hasta = lastBillableDay(account, cutoff);
        List<Installment> facturadas = planes.stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .filter(i -> i.getBilledCycle() == null)
                .filter(i -> !i.getDueDate().isAfter(hasta))
                .toList();
        facturadas.forEach(i -> i.billIn(cutoff.cycleNumber(), cutoff.paymentDueDate()));
        if (!facturadas.isEmpty()) {
            installments.saveAll(facturadas);
        }

        // 2) The cycle's obligation: what this statement asks for, and what delinquency is measured against.
        BigDecimal amountDue;
        BigDecimal saldo = cutoff.balanceAtCutoff() == null ? BigDecimal.ZERO : cutoff.balanceAtCutoff();
        // The rule follows the product, as the statement's layout does: a distributor line is asked for its placements'
        // principal and the period's charges even when its billing cycle sets a minimum rate.
        boolean distribuidora = DISTRIBUTOR_LINE.equalsIgnoreCase(account.getProductType());
        if (cutoff.minimumPayment() != null && !distribuidora) {
            // A card or a line: the minimum, never more than what is revolving, plus the plans' installments it bills.
            BigDecimal enPlanes = planes.stream()
                    .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                    .map(Installment::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal revolvente = saldo.subtract(enPlanes).max(BigDecimal.ZERO);
            BigDecimal cuotasDePlanes = facturadas.stream().map(Installment::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // A credit card's minimum has the Banxico floor (C13/2011): the same rule the statement prints.
            BigDecimal revolvingMinimum = CREDIT_CARD.equalsIgnoreCase(account.getProductType())
                    ? CardMinimumPayment.revolvingMinimum(cutoff.minimumPayment(), revolvente,
                            cutoff.principalAtCutoff() == null ? BigDecimal.ZERO
                                    : saldo.subtract(cutoff.principalAtCutoff()), account.getCreditLimit())
                    : cutoff.minimumPayment().min(revolvente);
            amountDue = revolvingMinimum.add(cuotasDePlanes);
        } else {
            // No minimum (a distributor line): the purchases and the plans' principal it bills, plus what the period
            // charged (interest, IVA, moratorium, fees). Asking for the installments' totals instead, which carry the
            // plan's interest for their own dates, let a distributor that paid every statement fall behind its plan.
            BigDecimal compras = porFacturar.stream().map(Disposition::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal capitalDePlanes = facturadas.stream().map(Installment::getPrincipalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cargos = ledger == null || cutoff.periodStart() == null ? BigDecimal.ZERO
                    : ledger.chargesBetween(cutoff.creditAccountId(), cutoff.periodStart(), cutoff.cutoffDate());
            amountDue = compras.add(capitalDePlanes).add(cargos.max(BigDecimal.ZERO));
            if (cutoff.balanceAtCutoff() != null) {
                amountDue = amountDue.min(saldo.max(BigDecimal.ZERO));
            }
        }
        amountDue = amountDue.setScale(2, RoundingMode.HALF_UP);

        if (amountDue.compareTo(CENTAVO) >= 0) {
            // La cuota del ciclo cuelga de la CUENTA, no de una disposición: representa al ciclo
            // entero, no a una compra. Es lo que el envejecido y el moratorio encuentran para vencer.
            Installment delCiclo = Installment.of(cutoff.creditAccountId(), cutoff.cycleNumber(),
                    cutoff.paymentDueDate(), amountDue, BigDecimal.ZERO);
            delCiclo.billAsStatement(cutoff.cycleNumber(), cutoff.cutoffDate(), cutoff.paymentDueDate());
            installments.saveAll(List.of(delCiclo));
        }

        porFacturar.forEach(d -> d.billInCycle(cutoff.cycleNumber()));
        porFacturar.forEach(dispositions::save);

        log.info("Ciclo {} facturado cuenta={} compras={} cuotas de planes={} exigible del ciclo={} vence={}",
                cutoff.cycleNumber(), cutoff.creditAccountId(), porFacturar.size(), facturadas.size(),
                amountDue, cutoff.paymentDueDate());
    }

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");
    private static final String CREDIT_CARD = "CREDIT_CARD";
    private static final String DISTRIBUTOR_LINE = "DISTRIBUTOR_LINE";

    /** The last day an installment's own date may fall on for this statement to bill it. */
    private LocalDate lastBillableDay(CreditAccount account, CorteCerrado cutoff) {
        BillingCycle cycle = configResolver == null ? null
                : configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                        .map(ProductConfigVersion::getBillingCycle).orElse(null);
        return BillingCycle.lastBillableDay(cycle, cutoff.cutoffDate(), cutoff.paymentDueDate());
    }

    /** ¿Ya existe la cuota de este ciclo? Es la guarda de idempotencia del corte. */
    private boolean alreadyBilled(CorteCerrado cutoff) {
        // A cycle whose minimum was zero has no cycle installment; it is still billed if its purchases or
        // its plans' installments carry the cycle.
        boolean cuotaDelCiclo = installments.findByScheduleIdOrdered(cutoff.creditAccountId()).stream()
                .anyMatch(i -> i.getInstallmentNumber() == cutoff.cycleNumber());
        if (cuotaDelCiclo) {
            return true;
        }
        List<Disposition> deLaCuenta = dispositions.findByCreditAccountId(cutoff.creditAccountId());
        if (deLaCuenta.stream().anyMatch(d -> Integer.valueOf(cutoff.cycleNumber()).equals(d.getBilledCycle()))) {
            return true;
        }
        return !deLaCuenta.isEmpty() && installments.findByScheduleIds(
                        deLaCuenta.stream().map(Disposition::getDispositionId).toList()).stream()
                .anyMatch(i -> Integer.valueOf(cutoff.cycleNumber()).equals(i.getBilledCycle()));
    }

    private static LocalDate date(Disposition d) {
        return d.getCreatedAt().atZone(BusinessClock.current().zone()).toLocalDate();
    }
}
