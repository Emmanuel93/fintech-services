package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
 */
@Service
public class CycleBillingService implements CloseCutoffCycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(CycleBillingService.class);

    private final CreditAccountRepository accounts;
    private final DispositionRepository dispositions;
    private final InstallmentRepository installments;

    public CycleBillingService(CreditAccountRepository accounts,
                               DispositionRepository dispositions,
                               InstallmentRepository installments) {
        this.accounts     = accounts;
        this.dispositions = dispositions;
        this.installments = installments;
    }

    @Override
    @Transactional
    public void onCutoffClosed(CorteCerrado corte) {
        CreditAccount cuenta = accounts.findById(corte.creditAccountId()).orElse(null);
        if (cuenta == null) {
            log.warn("cutoff-closed sin cuenta conocida creditAccountId={} — ignorado",
                    corte.creditAccountId());
            return;
        }
        if (!cuenta.isRevolving()) {
            // Un producto a plazo ya tiene su exigible en el plan de amortización. Fabricarle otro
            // aquí sería cobrarle dos veces la misma cuota.
            log.debug("Corte de cuenta a plazo {} — su exigible sale del plan", corte.creditAccountId());
            return;
        }

        // El ciclo se factura UNA vez. `uq_installment_number` es (schedule_id, número), y aquí el
        // número es el del ciclo: una segunda cuota del mismo ciclo choca contra la restricción y
        // tumba el procesamiento del corte entero.
        //
        // Pasa de verdad: el corte se reentrega —Kafka entrega al menos una vez— y entretanto puede
        // haber llegado tarde el evento de una compra con fecha anterior al corte. Esa compra
        // pertenece al ciclo SIGUIENTE, no a uno ya facturado y ya comunicado al cliente.
        if (yaFacturado(corte)) {
            log.info("El ciclo {} de la cuenta {} ya estaba facturado — las compras rezagadas "
                            + "entran al corte siguiente",
                    corte.cycleNumber(), corte.creditAccountId());
            return;
        }

        List<Disposition> porFacturar = dispositions
                .findByCreditAccountId(corte.creditAccountId()).stream()
                .filter(Disposition::esExigibleEnElCorte)
                .filter(d -> !fecha(d).isAfter(corte.cutoffDate()))
                .toList();

        if (porFacturar.isEmpty()) {
            log.debug("Ciclo {} de la cuenta {} sin compras revolventes que exigir",
                    corte.cycleNumber(), corte.creditAccountId());
            return;
        }

        BigDecimal exigible = porFacturar.stream()
                .map(Disposition::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // La cuota del ciclo cuelga de la CUENTA, no de una disposición: representa al ciclo
        // entero, no a una compra. Es lo que el envejecido y el moratorio encuentran para vencer.
        installments.saveAll(List.of(Installment.of(
                corte.creditAccountId(), corte.cycleNumber(), corte.paymentDueDate(),
                exigible, BigDecimal.ZERO)));

        porFacturar.forEach(d -> d.facturarEnCiclo(corte.cycleNumber()));
        porFacturar.forEach(dispositions::save);

        log.info("Ciclo {} facturado cuenta={} compras={} exigible={} vence={}",
                corte.cycleNumber(), corte.creditAccountId(), porFacturar.size(),
                exigible, corte.paymentDueDate());
    }

    /** ¿Ya existe la cuota de este ciclo? Es la guarda de idempotencia del corte. */
    private boolean yaFacturado(CorteCerrado corte) {
        return installments.findByScheduleIdOrdered(corte.creditAccountId()).stream()
                .anyMatch(i -> i.getInstallmentNumber() == corte.cycleNumber());
    }

    private static LocalDate fecha(Disposition d) {
        return d.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
