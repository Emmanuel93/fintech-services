package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.WalletClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Lo que la colocación necesita de afuera.
 *
 * <p>{@link PlacementService} es puro: mueve la máquina de estados y no sabe que existen otros
 * servicios. Este orquestador es su contraparte — resuelve la línea, cotiza contra el producto,
 * pide la disposición y arma las métricas— y deja al agregado libre de HTTP.
 *
 * <p>La separación no es ceremonia: es lo que permite que los 145 tests de la máquina de estados
 * corran sin levantar un solo cliente.
 */
@Service
public class PlacementOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlacementOrchestrator.class);

    private final PlacementLifecycleUseCase lifecycle;
    private final PlacementRepository placements;
    private final CreditPortfolioClient creditPortfolio;
    private final WalletClient wallet;
    private final BeneficiaryProperties props;

    public PlacementOrchestrator(PlacementLifecycleUseCase lifecycle,
                                 PlacementRepository placements,
                                 CreditPortfolioClient creditPortfolio,
                                 WalletClient wallet,
                                 BeneficiaryProperties props) {
        this.lifecycle       = lifecycle;
        this.placements      = placements;
        this.creditPortfolio = creditPortfolio;
        this.wallet          = wallet;
        this.props           = props;
    }

    // ── Alta ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Crea la colocación: resuelve la línea, cotiza el pago quincenal y la deja invitada.
     *
     * <p>La línea <b>no se descuenta aquí</b>. La promesa está escrita en la pantalla 23 —«nada
     * queda apartado mientras tanto»— y se cumple porque el descuento vive en
     * {@link #approve(UUID, UUID, boolean)}.
     */
    public Placement create(UUID distributorPartyId, String fullName, String phone,
                            String relationship, BigDecimal amount, int termFortnights) {
        CreditPortfolioClient.CreditAccount line = requireLine(distributorPartyId);

        BigDecimal available = line.availableCredit() == null ? BigDecimal.ZERO : line.availableCredit();
        BigDecimal payment = fortnightlyPayment(amount, termFortnights, line.nominalRate());

        return lifecycle.create(new CreatePlacementCommand(
                distributorPartyId,
                line.creditAccountId(),
                fullName,
                phone,
                relationship,
                amount,
                termFortnights,
                payment,
                VerificationMode.SELF_SERVICE_LINK,
                Instant.now().plus(props.getInviteTtlDays(), ChronoUnit.DAYS),
                limitsFor(line),
                available,
                UUID.randomUUID().toString()));
    }

    // ── Decisión ─────────────────────────────────────────────────────────────────────────────

    /**
     * El distribuidor aprueba: se descuenta la línea y sale el dinero.
     *
     * <p>El orden importa. Primero se mueve el agregado a {@code APPROVED} —que es lo que exige la
     * aceptación de riesgo— y sólo entonces se pide la disposición. Al revés, un fallo del
     * agregado dejaría dinero enviado sin decisión registrada.
     */
    public Placement approve(UUID placementId, UUID distributorPartyId, boolean riskAcknowledged) {
        Placement placement = lifecycle.approve(
                placementId, distributorPartyId, riskAcknowledged, UUID.randomUUID());

        try {
            wallet.requestThirdPartyDisposition(
                    placement.getDistributorCreditAccountId(),
                    distributorPartyId,
                    placement.getAmount(),
                    placement.getBeneficiaryPartyId(),
                    placement.getBeneficiaryClabe(),
                    placement.getTermFortnights());
        } catch (RuntimeException e) {
            // La línea pudo haberse agotado entre la aprobación y este momento: dos colocaciones
            // aprobadas contra el mismo cupo es exactamente el caso que `FAILED` existe para
            // registrar, con motivo y sin dejar la colocación colgada en APPROVED.
            log.error("La disposición de la colocación {} falló: {}", placementId, e.getMessage());
            lifecycle.fail(placementId, "No se pudo disponer de la línea: " + e.getMessage());
            throw e;
        }

        // La disposición es asíncrona (wallet responde 202): la colocación queda en DISBURSING y
        // el listener de `disposition-completed` la cierra. El id definitivo lo trae ese evento;
        // mientras tanto se usa el del placement para no dejar el campo nulo, que el CHECK de la
        // base prohíbe en este estado.
        return lifecycle.markDisbursing(placementId, placement.getPlacementId());
    }

    /**
     * Vuelve a mandarle la liga.
     *
     * <p><b>No extiende los 7 días.</b> Si extendiera, la vigencia sería infinita a punta de
     * reenvíos y la promesa de que una liga sin usar caduca dejaría de ser cierta.
     */
    public Placement resendInvite(UUID placementId, UUID distributorPartyId) {
        Placement placement = lifecycle.findById(placementId);
        if (!placement.getDistributorPartyId().equals(distributorPartyId)) {
            throw new PlacementAccessDeniedException(placementId, distributorPartyId);
        }
        if (placement.getStatus() != PlacementStatus.INVITED
                && placement.getStatus() != PlacementStatus.KYC_IN_PROGRESS) {
            throw new PlacementNotReadyException(
                    "Sólo se puede reenviar la liga mientras la verificación no ha terminado");
        }
        log.info("Reenvío de liga solicitado para la colocación {}", placementId);
        return placement;
    }

    public Placement reject(UUID placementId, UUID distributorPartyId, String reason) {
        return lifecycle.reject(placementId, distributorPartyId,
                reason == null || reason.isBlank() ? "El distribuidor decidió no colocar" : reason);
    }

    // ── Consultas ────────────────────────────────────────────────────────────────────────────

    /** El resumen de la línea que pinta la tarjeta del home. */
    public LineSummary lineSummary(UUID distributorPartyId) {
        return creditPortfolio.findActiveDistributorLine(distributorPartyId)
                .map(line -> {
                    BigDecimal authorized = orZero(line.creditLimit());
                    BigDecimal available  = orZero(line.availableCredit());
                    BigDecimal placed     = authorized.subtract(available).max(BigDecimal.ZERO);

                    List<Placement> live = placements
                            .findByDistributorPartyIdOrderByCreatedAtDesc(distributorPartyId)
                            .stream()
                            .filter(p -> p.getStatus() == PlacementStatus.DISBURSED)
                            .toList();

                    // Lo que va a cobrar en el corte es la suma de las quincenas vivas; lo que le
                    // toca pagar a Kredius es eso menos su comisión, porque no se le cobra aparte:
                    // se le descuenta de lo que entrega.
                    BigDecimal toCollect = live.stream()
                            .map(Placement::getFortnightlyPayment)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal commission = toCollect.multiply(props.getCommissionOnTimeRate())
                            .setScale(2, RoundingMode.HALF_UP);

                    return new LineSummary(authorized, placed, available, toCollect,
                            toCollect.subtract(commission).max(BigDecimal.ZERO),
                            commission, line.paymentDueDate(), line.daysDelinquent());
                })
                // Sin línea no es error: es quien pidió crédito para sí. La app lo lee como
                // «este titular no coloca» y esconde el módulo entero.
                .orElse(LineSummary.none());
    }

    /**
     * Las métricas que no sabe la colocación: cuántas quincenas van, cuánto se atrasó y cuánto
     * lleva ganado el distribuidor.
     */
    public PlacementMetrics metricsOf(Placement placement) {
        if (placement.getDispositionId() == null) return PlacementMetrics.none();

        return creditPortfolio
                .scheduleOf(placement.getDistributorCreditAccountId(), placement.getDispositionId())
                .map(s -> {
                    int paid = s.paidInstallments() == null ? 0 : s.paidInstallments();
                    int late = s.daysPastDue() == null ? 0 : s.daysPastDue();
                    // La comisión se devenga sobre lo cobrado, y sólo si se cobró a tiempo. La
                    // escalera completa (17/14/10/6/0) la resuelve commission-service; aquí se
                    // aplica el caso simple para que la fila no mienta mientras esa fase llega.
                    BigDecimal rate = late > 0 ? BigDecimal.ZERO : props.getCommissionOnTimeRate();
                    BigDecimal accrued = placement.getFortnightlyPayment()
                            .multiply(BigDecimal.valueOf(paid))
                            .multiply(rate)
                            .setScale(2, RoundingMode.HALF_UP);
                    return new PlacementMetrics(accrued, paid, late);
                })
                .orElse(PlacementMetrics.none());
    }

    // ── Cotización ───────────────────────────────────────────────────────────────────────────

    /**
     * Anualidad francesa sobre la tasa quincenal.
     *
     * <p>Es la cifra que va al contrato. La app la estima mientras se arrastra el slider, pero si
     * el teléfono y el servidor redondearan distinto, el distribuidor le prometería a su clienta
     * un número y ella firmaría otro.
     */
    BigDecimal fortnightlyPayment(BigDecimal amount, int termFortnights, BigDecimal accountRate) {
        BigDecimal annual = accountRate != null && accountRate.signum() > 0
                ? accountRate
                : props.getDefaultAnnualRate();
        BigDecimal i = annual.divide(BigDecimal.valueOf(props.getPeriodsPerYear()), MathContext.DECIMAL64);

        if (i.signum() == 0) {
            return amount.divide(BigDecimal.valueOf(termFortnights), 2, RoundingMode.HALF_UP);
        }
        // pago = P·i / (1 − (1+i)^−n)
        BigDecimal onePlusI = BigDecimal.ONE.add(i);
        BigDecimal factor = BigDecimal.ONE.divide(onePlusI.pow(termFortnights, MathContext.DECIMAL64),
                MathContext.DECIMAL64);
        BigDecimal denominator = BigDecimal.ONE.subtract(factor);
        return amount.multiply(i, MathContext.DECIMAL64)
                .divide(denominator, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private PlacementLimits limitsFor(CreditPortfolioClient.CreditAccount line) {
        // El tope por beneficiario lo fija el producto. Mientras el catálogo no lo publique, se
        // usa el configurado: sin un segundo límite, quien tenga línea de $500,000 podría
        // colocárselos completos a un solo cliente.
        List<Integer> terms = props.getTermFortnights();
        return new PlacementLimits(
                props.getMinPlacementAmount(),
                props.getMaxPlacementAmount().min(orZero(line.creditLimit()).max(BigDecimal.ONE)),
                props.getAmountStep().intValue(),
                terms.getFirst(),
                terms.getLast(),
                terms.size() > 1 ? terms.get(1) - terms.getFirst() : 1);
    }

    private CreditPortfolioClient.CreditAccount requireLine(UUID distributorPartyId) {
        return creditPortfolio.findActiveDistributorLine(distributorPartyId)
                .orElseThrow(() -> new PlacementValidationException(
                        "No tienes una línea de colocación activa"));
    }

    private static BigDecimal orZero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Lo que la app necesita para pintar «MI LÍNEA DE DISTRIBUIDOR». */
    public record LineSummary(
            BigDecimal authorized,
            BigDecimal placed,
            BigDecimal available,
            BigDecimal toCollect,
            BigDecimal dueToKredius,
            BigDecimal commissionAccrued,
            java.time.LocalDate nextDueDate,
            int daysPastDue) {

        /** Sin línea autorizada. La app lo distingue por `authorized == 0`. */
        static LineSummary none() {
            return new LineSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
        }
    }
}
