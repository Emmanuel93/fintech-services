package com.fintech.charges.application.service;

import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * BK-26 · el interés que la compra devengó mientras fue revolvente se reversa: el plan lo sustituye.
 *
 * <h2>Por qué NO se reversan los cargos diarios, que es lo que el plan suponía</h2>
 *
 * <p>El plan decía «cada devengo diario entre la compra y el diferimiento se reversa». Al ir a
 * hacerlo, el supuesto no se sostiene: el devengo ordinario de este servicio es <b>por cuenta</b>,
 * no por disposición. Un cargo diario de una tarjeta cubre el saldo completo de la línea —todas las
 * compras vivas— y reversarlo entero devolvería también el interés de las compras que <b>no</b> se
 * difirieron.
 *
 * <p>Lo correcto es reversar la parte <b>atribuible</b> a la compra diferida:
 *
 * <pre>
 * interés a reversar = importe de la compra × tasa / 360 × días que fue revolvente
 * </pre>
 *
 * <p>Es la misma aritmética con la que se devengó, aplicada a la porción que corresponde. Y se
 * registra como un cargo propio en negativo, no editando los diarios: la bitácora de cargos es
 * append-only y borrar lo que ya se cobró dejaría el mayor sin explicación.
 *
 * <h2>El IVA va con él</h2>
 *
 * <p>El IVA de un crédito grava el interés. Si se reversa el interés y no su IVA, queda un impuesto
 * trasladado sobre un ingreso que ya no existe — y eso se descubre en la declaración, no antes.
 */
@Service
public class DeferralInterestReversalService {

    private static final Logger log = LoggerFactory.getLogger(DeferralInterestReversalService.class);
    private static final BigDecimal DIAS_DEL_ANIO = new BigDecimal("360");

    private final AccrualScheduleRepository scheduleRepository;
    private final ChargeRecordRepository chargeRecordRepository;
    private final ChargeEventPublisher eventPublisher;
    private final ChargesProperties properties;

    public DeferralInterestReversalService(AccrualScheduleRepository scheduleRepository,
                                           ChargeRecordRepository chargeRecordRepository,
                                           ChargeEventPublisher eventPublisher,
                                           ChargesProperties properties) {
        this.scheduleRepository     = scheduleRepository;
        this.chargeRecordRepository = chargeRecordRepository;
        this.eventPublisher         = eventPublisher;
        this.properties             = properties;
    }

    @Transactional
    public void reversarPorDiferimiento(UUID creditAccountId, UUID dispositionId,
                                        BigDecimal importeDeLaCompra,
                                        LocalDate desde, LocalDate hasta) {
        AccrualSchedule schedule = scheduleRepository.findByCreditAccountId(creditAccountId)
                .orElse(null);
        if (schedule == null) {
            log.warn("disposition-deferred sin calendario de devengo cuenta={} — nada que reversar",
                    creditAccountId);
            return;
        }

        long dias = ChronoUnit.DAYS.between(desde, hasta);
        if (dias <= 0) {
            // Se difirió el mismo día de la compra: no alcanzó a devengar nada.
            log.info("Compra {} diferida el mismo día — sin interés que reversar", dispositionId);
            return;
        }

        BigDecimal tasaDiaria = schedule.getNominalRate().divide(DIAS_DEL_ANIO, 10, RoundingMode.HALF_UP);
        BigDecimal interes = importeDeLaCompra
                .multiply(tasaDiaria)
                .multiply(BigDecimal.valueOf(dias))
                .setScale(2, RoundingMode.HALF_UP);

        if (interes.signum() <= 0) {
            return;
        }
        BigDecimal iva = interes.multiply(properties.getVatRate()).setScale(2, RoundingMode.HALF_UP);

        // En negativo y como cargo propio: la bitácora es append-only, y editar los diarios dejaría
        // al mayor sin poder explicar de dónde salió la diferencia.
        ChargeRecord reversa = ChargeRecord.create(
                creditAccountId, schedule.getObligorPartyId(),
                ChargeType.ORDINARY_INTEREST, importeDeLaCompra, schedule.getNominalRate(),
                (int) dias, interes.negate(), BigDecimal.ZERO, hasta, null);
        chargeRecordRepository.save(reversa);

        ChargeRecord reversaIva = ChargeRecord.createIva(
                creditAccountId, schedule.getObligorPartyId(),
                iva.negate(), hasta, reversa.getChargeId());
        chargeRecordRepository.save(reversaIva);

        eventPublisher.publishChargeReversed(reversa.getChargeId().toString(), creditAccountId,
                ChargeType.ORDINARY_INTEREST.name(), interes, false);
        eventPublisher.publishChargeReversed(reversaIva.getChargeId().toString(), creditAccountId,
                ChargeType.IVA.name(), iva, false);

        log.info("Interés revolvente reversado por diferimiento disposición={} cuenta={} días={} "
                        + "interés={} iva={}",
                dispositionId, creditAccountId, dias, interes, iva);
    }
}
