package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.PaymentOptions;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Saltar un pago: se corre el compromiso y <b>no se genera mora</b>.
 *
 * <p><b>Correr las que vienen detrás, no sólo la saltada.</b> Si se moviera únicamente la cuota
 * elegida, quedaría encima de la siguiente y el cliente tendría dos vencimientos el mismo día — que
 * es lo contrario de un respiro. Se recorre el resto del calendario un período.
 *
 * <p><b>El modo lo decide el producto, no el cliente.</b> {@code GIFT} no devenga el período
 * saltado (recompensa real); {@code DEFERRAL} sigue devengando y sólo aplaza. Dejarlo a elección de
 * quien pide convertiría una decisión de producto en una preferencia.
 */
@Service
public class SkipPaymentService implements SkipPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(SkipPaymentService.class);

    private final CreditAccountRepository accounts;
    private final InstallmentRepository installments;
    private final DispositionRepository dispositions;
    private final ProductConfigResolver configResolver;

    public SkipPaymentService(CreditAccountRepository accounts,
                              InstallmentRepository installments,
                              DispositionRepository dispositions,
                              ProductConfigResolver configResolver) {
        this.accounts       = accounts;
        this.installments   = installments;
        this.dispositions   = dispositions;
        this.configResolver = configResolver;
    }

    @Override
    @Transactional
    public void skip(SaltarPago cmd) {
        CreditAccount account = accounts.findById(cmd.creditAccountId()).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta " + cmd.creditAccountId()));

        ProductConfigVersion config = configResolver
                .resolveForAccount(account.getProductCode(), account.getProductVersion())
                .orElseThrow(() -> new SkipNotAllowedException(
                        "Sin configuración del producto " + account.getProductCode()));
        PaymentOptions opciones = config.getCapabilities().options();

        if (!opciones.skipPaymentEnabled()) {
            throw new SkipNotAllowedException(
                    "El producto " + account.getProductCode() + " no admite saltar pagos");
        }

        List<Installment> calendario = scheduleOf(account);
        Installment aSaltar = calendario.stream()
                .filter(i -> i.getInstallmentId().equals(cmd.installmentId()))
                .findFirst()
                .orElseThrow(() -> new SkipNotAllowedException(
                        "La cuota " + cmd.installmentId() + " no es de esta cuenta"));

        int yaSaltadas = (int) calendario.stream().filter(Installment::wasSkipped).count();
        int tope = opciones.maxSkipsPerCycle() != null ? opciones.maxSkipsPerCycle() : 0;
        if (yaSaltadas >= tope) {
            // Un tope que no se aplica no es un tope.
            throw new SkipNotAllowedException(
                    "Ya se usaron los " + tope + " saltos que permite el producto");
        }

        // Un período de la cadencia del producto, no un mes fijo: una línea quincenal se corre
        // quince días, no treinta.
        LocalDate newDueDate = AmortizationEngine.firstDueDate(
                aSaltar.getDueDate(), config.getPaymentFrequency());

        aSaltar.skip(opciones.skipMode(), newDueDate);

        // Las de atrás se corren también: dejar la saltada encima de la siguiente le daría al
        // cliente dos vencimientos el mismo día.
        calendario.stream()
                .filter(i -> i.getInstallmentNumber() > aSaltar.getInstallmentNumber())
                .filter(i -> !i.wasSkipped())
                .forEach(i -> i.shiftDueDate(AmortizationEngine.firstDueDate(
                        i.getDueDate(), config.getPaymentFrequency())));

        installments.saveAll(calendario);

        log.info("Pago SALTADO cuenta={} cuota={} modo={} de {} a {} (salto {} de {})",
                account.getCreditAccountId(), aSaltar.getInstallmentNumber(), opciones.skipMode(),
                aSaltar.getOriginalDueDate(), newDueDate, yaSaltadas + 1, tope);
    }

    /**
     * Todo lo que esta cuenta puede tener que pagar.
     *
     * <p>Un crédito a plazo tiene un calendario y cuelga de la cuenta. Una revolvente tiene
     * <b>dos fuentes</b>, y mirar sólo una era un defecto:
     *
     * <ul>
     *   <li>las <b>cuotas de ciclo</b>, que cuelgan de la cuenta y las genera el corte (BK-27);</li>
     *   <li>los <b>planes de las compras diferidas</b>, uno por disposición.</li>
     * </ul>
     *
     * <p>Buscar sólo en las disposiciones dejaba a un tarjetahabiente sin poder saltar el pago de su
     * ciclo — que es exactamente el que querría saltar. Lo único que encontraba eran los planes de
     * compras que ya había diferido.
     */
    private List<Installment> scheduleOf(CreditAccount account) {
        List<Installment> ofTheAccount =
                installments.findByScheduleIdOrdered(account.getCreditAccountId());
        if (!account.isRevolving()) {
            return ofTheAccount;
        }
        List<UUID> calendarios = dispositions.findByCreditAccountId(account.getCreditAccountId())
                .stream().map(Disposition::getDispositionId).toList();

        List<Installment> todo = new java.util.ArrayList<>(ofTheAccount);
        todo.addAll(installments.findByScheduleIds(calendarios));
        return todo;
    }
}
