package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.WalletClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.text.NumberFormat;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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
    private final PlacementInviteService inviteService;
    private final PartyClient party;

    public PlacementOrchestrator(PlacementLifecycleUseCase lifecycle,
                                 PlacementRepository placements,
                                 CreditPortfolioClient creditPortfolio,
                                 WalletClient wallet,
                                 BeneficiaryProperties props,
                                 PlacementInviteService inviteService,
                                 PartyClient party) {
        this.lifecycle       = lifecycle;
        this.placements      = placements;
        this.creditPortfolio = creditPortfolio;
        this.wallet          = wallet;
        this.props           = props;
        this.inviteService   = inviteService;
        this.party           = party;
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

        // In arrears the line does not place: from the first day a statement is late until she pays what is overdue,
        // the same day she pays it. The portfolio enforces it too; saying it here is what the app shows her.
        if (line.blockedForPlacements()) {
            BigDecimal overdue = line.overdueStatementsAmount() == null ? BigDecimal.ZERO : line.overdueStatementsAmount();
            // A quita closed the line for good (her exit); arrears block it until she pays what is overdue.
            boolean closed = line.placementBlockReason() != null && line.placementBlockReason().startsWith("LINE_CLOSED_AFTER_QUITA");
            throw new PlacementValidationException(closed
                    ? "Tu línea quedó cerrada por el convenio de quita: ya no coloca"
                    : "Tu línea está bloqueada por atraso: paga lo vencido (" + MONEY.format(overdue) + ") para volver a colocar");
        }

        // A client she placed before (same phone, already verified) is known: his type's limits and the red list
        // are told at the door instead of after he went through the verification again.
        UUID knownClient = placements.findByDistributorPartyIdOrderByCreatedAtDesc(distributorPartyId).stream()
                .filter(p -> phone != null && phone.equals(p.getBeneficiaryPhone()) && p.getBeneficiaryPartyId() != null)
                .map(Placement::getBeneficiaryPartyId).findFirst().orElse(null);
        if (knownClient != null) {
            refusalFor(line.creditAccountId(), knownClient, amount, termFortnights, distributorPartyId)
                    .ifPresent(message -> { throw new PlacementValidationException(message); });
        }

        BigDecimal available = line.availableCredit() == null ? BigDecimal.ZERO : line.availableCredit();
        BigDecimal payment = fortnightlyPayment(amount, termFortnights, line.nominalRate());

        Placement placement = lifecycle.create(new CreatePlacementCommand(
                distributorPartyId,
                line.creditAccountId(),
                line.productCode(),
                fullName,
                phone,
                relationship,
                amount,
                termFortnights,
                payment,
                VerificationMode.SELF_SERVICE_LINK,
                BusinessClock.current().now().plus(props.getInviteTtlDays(), ChronoUnit.DAYS),
                limitsFor(line),
                available,
                UUID.randomUUID().toString()));

        // Crear es inseparable de acuñar la liga: toda colocación nace con una viva. El token en
        // claro NO va en `placement-invited` —un token en un tópico es un token en los logs de
        // todos los consumidores—. Entregarlo por WhatsApp queda pendiente en notifications (que
        // armará la URL sin que el secreto pase por Kafka); mientras tanto, la liga se obtiene
        // rotándola desde el soporte de pruebas.
        inviteService.mint(placement);
        return placement;
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

        // The client's side, asked before any money moves: his type's limits on her line and the red list. The
        // portfolio decides again when it authorizes; asking here is what lets her read why, in her words.
        if (placement.getBeneficiaryPartyId() != null) {
            var refused = refusalFor(placement.getDistributorCreditAccountId(), placement.getBeneficiaryPartyId(),
                    placement.getAmount(), placement.getTermFortnights(), distributorPartyId);
            if (refused.isPresent()) {
                log.warn("La colocación {} no se dispone: {}", placementId, refused.get());
                lifecycle.fail(placementId, refused.get());
                throw new PlacementValidationException(refused.get());
            }
        }

        try {
            Payee payee = payeeOf(placement);
            wallet.requestThirdPartyDisposition(
                    placement.getDistributorCreditAccountId(),
                    distributorPartyId,
                    placement.getAmount(),
                    placement.getBeneficiaryPartyId(),
                    placement.getBeneficiaryClabe(),
                    payee.name(),
                    payee.taxId(),
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
     * A nombre de quién sale el SPEI: la beneficiaria, con su nombre verificado y su RFC/CURP.
     *
     * <p>La disposición mandaba sólo la CLABE, y cartera completaba nombre y RFC con los de la
     * cuenta — la distribuidora. El dinero llegaba a Rosa Elena con la orden y el CEP a nombre de
     * Laura Méndez.
     *
     * <p>El nombre sale del Party —el que quedó tras el KYC— y, si party no contesta, del que se
     * capturó al colocar: ambos son de la beneficiaria, que es lo que importa. El RFC no tiene
     * respaldo: sin party viaja vacío, que SPEI admite, en vez de inventarse o heredarse.
     */
    /** Why the portfolio would refuse this placement to this client, in her words; empty when it fits or cannot tell. */
    Optional<String> refusalFor(UUID lineId, UUID client, BigDecimal amount, int termFortnights, UUID distributorPartyId) {
        return creditPortfolio.placementLimits(lineId, client, amount, termFortnights, distributorPartyId)
                .filter(l -> !l.canPlace())
                .map(l -> PlacementRefusal.inHerWords(l.reason(),
                        l.flagsOrEmpty().stream().allMatch(f -> distributorPartyId.equals(f.distributorPartyId()))));
    }

    Payee payeeOf(Placement placement) {
        PartyClient.PartyResponse ficha = placement.getBeneficiaryProspectId() == null
                ? null
                : party.findByProspect(placement.getBeneficiaryProspectId()).orElse(null);
        String verificado = ficha == null ? "" : ficha.fullName();
        String nombre = !verificado.isBlank() ? verificado : placement.getBeneficiaryFullName();
        if (ficha == null) {
            log.warn("Sin ficha de party para la beneficiaria de la colocación {}: el SPEI sale con "
                    + "el nombre capturado y sin RFC", placement.getPlacementId());
        }
        return new Payee(nombre, ficha == null ? null : ficha.taxId());
    }

    /** El titular de la CLABE de destino. */
    record Payee(String name, String taxId) {}

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
                    BigDecimal commission = toCollect.multiply(commissionRate(line))
                            .setScale(2, RoundingMode.HALF_UP);

                    return new LineSummary(authorized, placed, available, toCollect,
                            toCollect.subtract(commission).max(BigDecimal.ZERO),
                            commission, line.paymentDueDate(), line.daysDelinquent(),
                            line.blockedForPlacements(), line.placementBlockReason(),
                            line.overdueStatementsAmount() == null ? BigDecimal.ZERO : line.overdueStatementsAmount(),
                            commissionRate(line));
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
                    BigDecimal rate = late > 0 ? BigDecimal.ZERO : commissionRate(line(placement));
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
     * Anualidad francesa sobre la tasa quincenal, con el IVA del interés adentro.
     *
     * <p>Es la cifra que va al contrato. La app la estima mientras se arrastra el slider, pero si
     * el teléfono y el servidor redondearan distinto, el distribuidor le prometería a su clienta
     * un número y ella firmaría otro.
     *
     * <p>It is the payment of the portfolio's plan for a whole first fortnight: the plan carries the interest's IVA
     * inside a flat payment, and before this the quote left it out (at 95 % a year, $1,062.95 promised against the
     * $1,102.42 the plan asks for $10,000 at 12 fortnights). A placement made later in its fortnight bears only its
     * share of that first fortnight, so its plan asks a little less: the quote is what the client pays at most.
     */
    BigDecimal fortnightlyPayment(BigDecimal amount, int termFortnights, BigDecimal accountRate) {
        BigDecimal annual = accountRate != null && accountRate.signum() > 0
                ? accountRate
                : props.getDefaultAnnualRate();
        BigDecimal i = annual.divide(BigDecimal.valueOf(props.getPeriodsPerYear()), MathContext.DECIMAL64)
                .multiply(BigDecimal.ONE.add(props.getVatRate()), MathContext.DECIMAL64);

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
    /**
     * @param blocked        the line does not place today: a statement is unpaid past its due date
     * @param blockReason    why, in the portfolio's words; null when it is not blocked
     * @param overdueAmount  what she must pay to place again
     * @param commissionRate her share of what her clients pay, as the product sets it
     */
    public record LineSummary(
            BigDecimal authorized,
            BigDecimal placed,
            BigDecimal available,
            BigDecimal toCollect,
            BigDecimal dueToKredius,
            BigDecimal commissionAccrued,
            java.time.LocalDate nextDueDate,
            int daysPastDue,
            boolean blocked,
            String blockReason,
            BigDecimal overdueAmount,
            BigDecimal commissionRate) {

        /** Sin línea autorizada. La app lo distingue por `authorized == 0`. */
        static LineSummary none() {
            return new LineSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, false, null, BigDecimal.ZERO, null);
        }
    }

    /** The product's share of what her clients pay when the portfolio knows it; the configured one when it does not. */
    private BigDecimal commissionRate(CreditPortfolioClient.CreditAccount line) {
        return line != null && line.distributorCommissionRate() != null ? line.distributorCommissionRate()
                : props.getCommissionOnTimeRate();
    }

    /** The line a placement was made on, as the portfolio has it today; null when it cannot be read. */
    private CreditPortfolioClient.CreditAccount line(Placement placement) {
        try {
            return creditPortfolio.findActiveDistributorLine(placement.getDistributorPartyId()).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.of("es", "MX"));
}
