package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.DeferDispositionUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Diferir una compra: el titular la saca del exigible del corte y la convierte en compra a plazos.
 *
 * <p>Tres cosas pasan, y el orden importa:
 *
 * <ol>
 *   <li>La disposición pasa de {@code REVOLVING} a {@code AMORTIZED} — con eso <b>sale sola</b> del
 *       exigible del corte, sin restar nada.</li>
 *   <li>Se genera su calendario con la tasa de diferimiento del producto, que puede ser cero.</li>
 *   <li>Se publica el hecho para que {@code charges} <b>reverse el interés</b> que se devengó
 *       mientras la compra fue revolvente (BK-26).</li>
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
    public void diferir(DiferirCompra cmd) {
        CreditAccount cuenta = accounts.findById(cmd.creditAccountId()).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta " + cmd.creditAccountId()));

        Disposition compra = dispositions.findById(cmd.dispositionId()).orElseThrow(
                () -> new NoSuchElementException("Sin disposición " + cmd.dispositionId()));

        if (!compra.getCreditAccountId().equals(cuenta.getCreditAccountId())) {
            // No es un detalle de validación: sin esto, cualquiera con un id de disposición podría
            // diferir la compra de otra persona.
            throw new NoSePuedeDiferirException("La disposición no pertenece a esta cuenta");
        }

        ProductConfigVersion config = configResolver
                .resolveForAccount(cuenta.getProductCode(), cuenta.getProductVersion())
                .orElseThrow(() -> new NoSePuedeDiferirException(
                        "Sin configuración del producto " + cuenta.getProductCode()));
        OpcionesDePago opciones = config.getCapabilities().opciones();

        if (!opciones.admiteDiferir()) {
            throw new NoSePuedeDiferirException(
                    "El producto " + cuenta.getProductCode() + " no admite diferir compras");
        }
        if (!compra.esExigibleEnElCorte()) {
            // Ya se difirió, o ya se facturó en un corte. En el segundo caso el importe ya es
            // exigible: diferirlo entonces sería mover una deuda que el cliente ya debe.
            throw new NoSePuedeDiferirException(
                    "La compra " + cmd.dispositionId() + " ya no se puede diferir: "
                            + (compra.getBilledCycle() != null
                                    ? "se facturó en el ciclo " + compra.getBilledCycle()
                                    : "ya tiene plan"));
        }

        int plazo = opciones.plazoDeDiferimiento(cmd.termPeriods());
        BigDecimal tasa = opciones.tasaDeDiferimiento(plazo).orElseThrow(
                () -> new NoSePuedeDiferirException(
                        "El producto no tiene tasa de diferimiento para " + plazo + " pagos"));

        LocalDate desde = compra.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
        compra.diferir(plazo);
        dispositions.save(compra);

        String cadencia = config.getPaymentFrequency() != null && !config.getPaymentFrequency().isBlank()
                ? config.getPaymentFrequency() : "MONTHLY";

        List<Installment> plan = amortizationEngine.generate(
                compra.getDispositionId(), compra.getAmount(), tasa, plazo,
                cuenta.getAmortizationType() != null ? cuenta.getAmortizationType() : "FRENCH",
                cadencia, AmortizationEngine.firstDueDate(LocalDate.now(), cadencia),
                cuenta.getVatRate());
        installments.saveAll(plan);

        // BK-26 · lo devengado mientras fue revolvente se reversa: el plan lo sustituye. Cartera no
        // calcula cuánto — ese número es de charges, que es quien devengó.
        eventPublisher.publishDispositionDeferred(new DispositionDeferredEvent(
                compra.getDispositionId(), cuenta.getCreditAccountId(), cuenta.getObligorPartyId(),
                compra.getAmount(), plazo, tasa, desde, LocalDate.now()));

        log.info("Compra DIFERIDA dispositionId={} cuenta={} plazo={} tasa={} cuotas={}{}",
                compra.getDispositionId(), cuenta.getCreditAccountId(), plazo, tasa, plan.size(),
                tasa.signum() == 0 ? " (MESES SIN INTERESES)" : "");
    }
}
