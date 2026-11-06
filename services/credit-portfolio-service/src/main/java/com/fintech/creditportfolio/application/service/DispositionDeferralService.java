package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.application.port.in.DeferDispositionUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.PaymentOptions;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import com.fintech.shared.time.BusinessClock;

/**
 * Diferir una compra: el titular la saca del exigible del corte y la convierte en compra a plazos.
 *
 * <h2>Sólo el día de la compra</h2>
 *
 * Una compra que ya devengó no se parcializa. El cierre del día de la compra la mete al saldo que causa
 * interés; diferirla después obligaría a reversar ese interés, y la reversa parcial —la parte de un cargo
 * diario de la línea que le toca a una compra— es justo lo que no cuadra. Así que se difiere el mismo día
 * de negocio en que se hizo, antes de su primer cierre, y nunca hay interés que reversar.
 *
 * <p>Al diferir:
 *
 * <ol>
 *   <li>La disposición pasa de {@code REVOLVING} a {@code AMORTIZED} — con eso <b>sale sola</b> del
 *       exigible del corte, sin restar nada.</li>
 *   <li>Se genera su calendario con la tasa de diferimiento del producto, que puede ser cero.</li>
 *   <li>Se publica el hecho ({@code credit-portfolio.disposition-deferred}).</li>
 * </ol>
 */
@Service
public class DispositionDeferralService implements DeferDispositionUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispositionDeferralService.class);

    private final CreditAccountRepository accounts;
    private final DispositionRepository dispositions;
    private final InstallmentRepository installments;
    private final ProductConfigResolver configResolver;
    private final AmortizationEngine amortizationEngine;
    private final CreditPortfolioEventPublisher eventPublisher;

    public DispositionDeferralService(CreditAccountRepository accounts,
                                      DispositionRepository dispositions,
                                      InstallmentRepository installments,
                                      ProductConfigResolver configResolver,
                                      AmortizationEngine amortizationEngine,
                                      CreditPortfolioEventPublisher eventPublisher) {
        this.accounts           = accounts;
        this.dispositions       = dispositions;
        this.installments       = installments;
        this.configResolver     = configResolver;
        this.amortizationEngine = amortizationEngine;
        this.eventPublisher     = eventPublisher;
    }

    @Override
    @Transactional
    public void defer(DiferirCompra cmd) {
        CreditAccount account = accounts.findById(cmd.creditAccountId()).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta " + cmd.creditAccountId()));

        Disposition compra = dispositions.findById(cmd.dispositionId()).orElseThrow(
                () -> new NoSuchElementException("Sin disposición " + cmd.dispositionId()));

        if (!compra.getCreditAccountId().equals(account.getCreditAccountId())) {
            // No es un detalle de validación: sin esto, cualquiera con un id de disposición podría
            // diferir la compra de otra persona.
            throw new NoSePuedeDiferirException("La disposición no pertenece a esta cuenta");
        }

        ProductConfigVersion config = configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion())
                .orElseThrow(() -> new NoSePuedeDiferirException(
                        "Sin configuración del producto " + account.getProductCode()));
        PaymentOptions opciones = config.getCapabilities().options();

        if (!opciones.allowsDeferral()) {
            throw new NoSePuedeDiferirException(
                    "El producto " + account.getProductCode() + " no admite diferir compras");
        }
        if (!compra.isDueAtCutoff()) {
            // Ya se difirió, o ya se facturó en un corte. En el segundo caso el importe ya es
            // exigible: diferirlo entonces sería mover una deuda que el cliente ya debe.
            throw new NoSePuedeDiferirException(
                    "La compra " + cmd.dispositionId() + " ya no se puede diferir: "
                            + (compra.getBilledCycle() != null
                                    ? "se facturó en el ciclo " + compra.getBilledCycle()
                                    : "ya tiene plan"));
        }

        LocalDate desde = compra.getCreatedAt().atZone(BusinessClock.current().zone()).toLocalDate();
        LocalDate hoy = BusinessClock.current().today();
        if (desde.isBefore(hoy)) {
            // El cierre del día de la compra ya corrió, o puede estar corriendo: la compra causó interés.
            throw new NoSePuedeDiferirException("La compra " + cmd.dispositionId() + " ya devengó interés: sólo"
                    + " se puede diferir el día en que se hizo (" + desde + ")");
        }

        int plazo = opciones.deferralTerm(cmd.termPeriods());
        BigDecimal rate = opciones.deferralRate(plazo).orElseThrow(
                () -> new NoSePuedeDiferirException(
                        "El producto no tiene tasa de diferimiento para " + plazo + " pagos"));

        compra.defer(plazo);
        dispositions.save(compra);

        String cadencia = config.getPaymentFrequency() != null && !config.getPaymentFrequency().isBlank()
                ? config.getPaymentFrequency() : "MONTHLY";

        List<Installment> plan = amortizationEngine.generate(
                compra.getDispositionId(), compra.getAmount(), rate, plazo,
                account.getAmortizationType() != null ? account.getAmortizationType() : "FRENCH",
                cadencia, null,
                AmortizationEngine.firstDueDate(BusinessClock.current().today(), cadencia,
                        BillingCycle.semiMonthlyOf(config.getBillingCycle())),
                account.getVatRate(), BillingCycle.semiMonthlyOf(config.getBillingCycle()));
        installments.saveAll(plan);

        // Mismo día de la compra: no devengó nada que reversar.
        eventPublisher.publishDispositionDeferred(new DispositionDeferredEvent(
                compra.getDispositionId(), account.getCreditAccountId(), account.getObligorPartyId(),
                compra.getAmount(), plazo, rate, desde, BusinessClock.current().today()));

        log.info("Compra DIFERIDA dispositionId={} cuenta={} plazo={} tasa={} cuotas={}{}",
                compra.getDispositionId(), account.getCreditAccountId(), plazo, rate, plan.size(),
                rate.signum() == 0 ? " (MESES SIN INTERESES)" : "");
    }
}
