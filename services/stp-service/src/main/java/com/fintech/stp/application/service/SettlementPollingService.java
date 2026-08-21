package com.fintech.stp.application.service;

import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import com.fintech.stp.application.port.out.SettlementObservationRepository;
import com.fintech.stp.application.port.out.SigningKeyProvider;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.application.port.out.StpGatewayPort;
import com.fintech.stp.application.port.out.StpPaymentOrderEventRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.BeneficiaryNameMatcher;
import com.fintech.stp.domain.ObservedVia;
import com.fintech.stp.domain.SettlementObservation;
import com.fintech.stp.domain.SettlementObservationStatus;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpOrderStatusCode;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderEvent;
import com.fintech.stp.domain.StpPaymentOrderStatus;
import com.fintech.stp.domain.signing.CadenaOriginalBuilder;
import com.fintech.stp.domain.signing.ConciliacionFirma;
import com.fintech.stp.domain.signing.OrdenPagoFirma;
import com.fintech.stp.domain.signing.SignatureAlgorithm;
import com.fintech.stp.domain.signing.StpSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Consulta a STP el desenlace de las órdenes en vuelo.
 *
 * <p>Esto sustituye a los tres endpoints abiertos que el legado exponía para que STP le avisara.
 * Al invertir la dirección desaparece la superficie de entrada, y la autenticación del canal sale
 * gratis: la conexión TLS la abrimos nosotros contra un host conocido.
 *
 * <p>El costo es latencia — hasta un intervalo de poll. Para SPEI, cuya confirmación es de
 * naturaleza batch, es irrelevante.
 *
 * <p><strong>Las llamadas HTTP van fuera de transacción y cada observación se aplica en la suya.</strong>
 * Una corrida puede recorrer veinte páginas contra la red; sostener eso dentro de una transacción
 * ocuparía una conexión del pool todo ese tiempo, y —peor— una sola violación de restricción en una
 * entrada (dos réplicas viendo la misma conciliación) marcaría la transacción como rollback-only y
 * revertiría todas las liquidaciones de la corrida, con los eventos ya publicados.
 */
@Service
public class SettlementPollingService implements PollSettlementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettlementPollingService.class);

    private static final String TIPO_ORDEN_ENVIADAS = "E";

    private final StpPaymentOrderRepository orderRepository;
    private final StpPaymentOrderEventRepository eventRepository;
    private final SettlementObservationRepository observationRepository;
    private final StpCompanyRepository companyRepository;
    private final SigningKeyProvider signingKeyProvider;
    private final StpGatewayPort gateway;
    private final StpEventPublisher eventPublisher;
    private final StpProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SettlementPollingService(StpPaymentOrderRepository orderRepository,
                                     StpPaymentOrderEventRepository eventRepository,
                                     SettlementObservationRepository observationRepository,
                                     StpCompanyRepository companyRepository,
                                     SigningKeyProvider signingKeyProvider,
                                     StpGatewayPort gateway,
                                     StpEventPublisher eventPublisher,
                                     StpProperties properties,
                                     TransactionTemplate transactionTemplate,
                                     Clock clock) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.observationRepository = observationRepository;
        this.companyRepository = companyRepository;
        this.signingKeyProvider = signingKeyProvider;
        this.gateway = gateway;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public int pollInFlightOrders() {
        Instant now = clock.instant();

        List<PollGroup> groups = transactionTemplate.execute(status -> {
            List<StpPaymentOrder> inFlight = orderRepository.findInFlightOlderThan(
                    now.minus(properties.getPolling().getGrace()));
            if (inFlight.isEmpty()) {
                return List.of();
            }
            alertOnStuckOrders(now);
            // Una consulta por (empresa, día): agrupar evita pedirle a STP la misma página N veces.
            return inFlight.stream()
                    .map(order -> new PollGroup(order.getCompanyId(), order.getBusinessDate()))
                    .distinct()
                    .collect(Collectors.toCollection(java.util.ArrayList::new));
        });

        if (groups == null || groups.isEmpty()) {
            log.debug("No in-flight STP orders — skipping poll");
            return 0;
        }

        int applied = 0;
        for (PollGroup group : groups) {
            try {
                applied += pollGroup(group);
            } catch (RuntimeException e) {
                // Un fallo con una empresa no puede dejar sin consultar a las demás.
                log.error("Poll failed for companyId={} businessDate={}: {}",
                        group.companyId(), group.businessDate(), e.getMessage());
            }
        }
        return applied;
    }

    private int pollGroup(PollGroup group) {
        GroupContext context = transactionTemplate.execute(status -> {
            StpCompany company = companyRepository.findById(group.companyId()).orElseThrow();
            ConciliacionFirma firma = new ConciliacionFirma(
                    company.getStpEmpresa(), TIPO_ORDEN_ENVIADAS, group.businessDate());
            String sello = StpSigner.sign(CadenaOriginalBuilder.build(firma),
                    signingKeyProvider.activeSigningKey(company.getCompanyId()),
                    SignatureAlgorithm.SHA256_WITH_RSA);
            return new GroupContext(company, sello,
                    signingKeyProvider.activeVerificationKey(company.getCompanyId()));
        });

        // ── Red: fuera de cualquier transacción ──────────────────────────────
        List<StpGatewayPort.ReconciliationEntry> entries = new java.util.ArrayList<>();
        int page = 1;
        int seen = 0;
        int total;
        do {
            StpGatewayPort.ReconciliationPage response = gateway.queryReconciliation(
                    context.company().getStpEmpresa(), TIPO_ORDEN_ENVIADAS, group.businessDate(),
                    page, context.sello());
            total = response.total();
            seen += response.entries().size();
            entries.addAll(response.entries());
            page++;
        } while (seen < total && page <= properties.getPolling().getMaxPagesPerRun());

        if (seen < total) {
            // Sin esto, un día con más páginas de las permitidas se vería como "conciliado".
            log.warn("Reconciliation truncated for companyId={} businessDate={}: {} of {} entries read "
                            + "({} pages max). Remaining entries will be picked up next run.",
                    context.company().getCompanyId(), group.businessDate(), seen, total,
                    properties.getPolling().getMaxPagesPerRun());
        }

        // ── Aplicación: una transacción por observación ──────────────────────
        int applied = 0;
        for (StpGatewayPort.ReconciliationEntry entry : entries) {
            try {
                Boolean ok = transactionTemplate.execute(status ->
                        apply(context.company(), entry, context.verificationKey()));
                if (Boolean.TRUE.equals(ok)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                // Una entrada envenenada no puede tumbar el resto de la conciliación.
                log.error("No se pudo aplicar la observación trackingKey={} companyId={}: {}",
                        entry.claveRastreo(), context.company().getCompanyId(), e.getMessage());
            }
        }

        transactionTemplate.executeWithoutResult(status ->
                orderRepository.findByCompanyIdAndBusinessDate(group.companyId(), group.businessDate())
                        .forEach(order -> {
                            order.markPolled(clock.instant());
                            orderRepository.save(order);
                        }));
        return applied;
    }

    private record GroupContext(StpCompany company, String sello, Optional<PublicKey> verificationKey) {}

    private boolean apply(StpCompany company, StpGatewayPort.ReconciliationEntry entry,
                          Optional<PublicKey> verificationKey) {

        Optional<StpOrderStatusCode> outcome = StpOrderStatusCode.fromStpCode(entry.estado());
        if (outcome.isEmpty()) {
            return false;   // sigue en tránsito: no se adivina, se vuelve a consultar
        }

        String observedAtSource = String.valueOf(
                entry.tsLiquidacion() != null ? entry.tsLiquidacion() : entry.tsCaptura());

        // SO-02 — el poller relee lo mismo cada N minutos por diseño.
        if (observationRepository.existsByCompanyIdAndTrackingKeyAndObservedStatusAndObservedAtSource(
                company.getCompanyId(), entry.claveRastreo(), entry.estado(), observedAtSource)) {
            return false;
        }

        SettlementObservation observation = SettlementObservation.record(
                company.getCompanyId(), entry.claveRastreo(), entry.estado(), observedAtSource,
                entry.causaDevolucion(), entry.urlCEP(), entry.nombreCep(), entry.sello(),
                entry.rawPayload(), ObservedVia.POLL_RECONCILIATION);

        // La orden se busca en la base, NO en el subconjunto que pasó la ventana de gracia. Con un
        // mapa parcial, una orden recién enviada que STP ya reportaba quedaba grabada como UNMATCHED
        // — y la guarda de deduplicación SO-02 impedía volver a mirarla en las corridas siguientes.
        // La orden se quedaba en vuelo para siempre con el dinero ya fuera.
        Optional<StpPaymentOrder> found = orderRepository.findByCompanyIdAndTrackingKey(
                company.getCompanyId(), entry.claveRastreo());
        if (found.isEmpty()) {
            // SO-03 — puede ser una orden del legado durante el corte, o una que perdimos.
            observation.markUnmatched("No existe orden para la clave de rastreo " + entry.claveRastreo());
            observationRepository.save(observation);
            log.warn("UNMATCHED settlement observation companyId={} trackingKey={} estado={}",
                    company.getCompanyId(), entry.claveRastreo(), entry.estado());
            return false;
        }
        StpPaymentOrder order = found.get();
        if (order.isTerminal()) {
            observation.markDuplicate();
            observationRepository.save(observation);
            return false;
        }

        // SO-04 — integridad y no repudio.
        //
        // Se verifica contra `signedPayload`, que es el detalle SIN el campo `sello`: verificar
        // contra un payload que contiene su propia firma es imposible por construcción.
        //
        // La política es configurable porque la cadena exacta que STP firma en la conciliación no
        // está confirmada contra su especificación. Bloquear contra una suposición sería peor que
        // no verificar: dinero que ya salió del banco y que nunca se confirmaría.
        StpProperties.SignaturePolicy policy = properties.getSettlement().getSignaturePolicy();
        if (policy != StpProperties.SignaturePolicy.OFF
                && entry.sello() != null && !entry.sello().isBlank()) {
            boolean valid = verificationKey
                    .map(key -> StpSigner.verify(entry.signedPayload(), entry.sello(), key,
                            SignatureAlgorithm.SHA256_WITH_RSA))
                    .orElse(false);
            if (!valid) {
                if (policy == StpProperties.SignaturePolicy.ENFORCE) {
                    observation.markSignatureInvalid("El sello no verifica contra la llave pública de STP");
                    observationRepository.save(observation);
                    log.error("SIGNATURE_INVALID settlement observation companyId={} trackingKey={}",
                            company.getCompanyId(), entry.claveRastreo());
                    return false;
                }
                observation.markSignatureUnverified(verificationKey.isPresent()
                        ? "El sello no verifica; política WARN: se aplica el cambio de estado"
                        : "Sin llave de verificación registrada; política WARN");
                log.warn("SIGNATURE_UNVERIFIED companyId={} trackingKey={} — se aplica igualmente "
                                + "(política {}). Cerrar esta brecha antes de producción.",
                        company.getCompanyId(), entry.claveRastreo(), policy);
            } else {
                observation.markSignatureVerified();
            }
        }

        StpPaymentOrderStatus from = order.status();
        switch (outcome.get()) {
            case LIQUIDADA -> {
                boolean namesMatch = BeneficiaryNameMatcher.matchesAllowingTruncation(
                        order.getBeneficiaryNameSent(), entry.nombreCep(),
                        OrdenPagoFirma.MAX_NOMBRE_BENEFICIARIO);
                Instant settledAt = toInstant(entry.tsLiquidacion());
                order.settle(settledAt, entry.urlCEP(), namesMatch);
                orderRepository.save(order);
                eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                        StpPaymentOrderStatus.SETTLED, entry.estado(), entry.urlCEP(), "PROVIDER"));
                eventPublisher.publishOrderSettled(order, settledAt, entry.urlCEP(), entry.nombreCep(),
                        namesMatch, ObservedVia.POLL_RECONCILIATION);
                if (!namesMatch) {
                    log.warn("Beneficiary name mismatch trackingKey={} sent='{}' cep='{}'",
                            order.getTrackingKey(), order.getBeneficiaryNameSent(), entry.nombreCep());
                }
                log.info("SETTLED trackingKey={} settledAt={}", order.getTrackingKey(), settledAt);
            }
            case DEVUELTA -> {
                order.markReturned(entry.causaDevolucion());
                orderRepository.save(order);
                eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                        StpPaymentOrderStatus.RETURNED, entry.causaDevolucion(), entry.estado(), "PROVIDER"));
                eventPublisher.publishOrderReturned(order, StpPaymentOrderStatus.RETURNED.name(),
                        entry.causaDevolucion(), ObservedVia.POLL_RECONCILIATION);
                log.warn("RETURNED trackingKey={} cause={}", order.getTrackingKey(), entry.causaDevolucion());
            }
            case CANCELADA -> {
                order.markCancelled(entry.causaDevolucion());
                orderRepository.save(order);
                eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                        StpPaymentOrderStatus.CANCELLED, entry.causaDevolucion(), entry.estado(), "PROVIDER"));
                eventPublisher.publishOrderReturned(order, StpPaymentOrderStatus.CANCELLED.name(),
                        entry.causaDevolucion(), ObservedVia.POLL_RECONCILIATION);
                log.warn("CANCELLED trackingKey={} cause={}", order.getTrackingKey(), entry.causaDevolucion());
            }
        }

        // markApplied sólo si no quedó marcada como no verificada: ese estado tiene que sobrevivir
        // para que alguien lo vea en la bitácora.
        if (!SettlementObservationStatus.SIGNATURE_UNVERIFIED.name().equals(observation.getStatus())) {
            observation.markApplied();
        }
        observationRepository.save(observation);
        return true;
    }

    /** SO-06: se alerta, pero NUNCA se marca liquidada por timeout. */
    private void alertOnStuckOrders(Instant now) {
        List<StpPaymentOrder> stuck = orderRepository.findInFlightStuckSince(
                now.minus(properties.getPolling().getSettlementTimeout()));
        for (StpPaymentOrder order : stuck) {
            log.error("STP order stuck in flight beyond settlement timeout: trackingKey={} status={} sentAt={}",
                    order.getTrackingKey(), order.getStatus(), order.getSentAt());
        }
    }

    private static Instant toInstant(Long epochMillis) {
        return epochMillis != null ? Instant.ofEpochMilli(epochMillis) : Instant.now();
    }

    private record PollGroup(UUID companyId, LocalDate businessDate) {}

    /** Sólo para el barrido de cierre, que trabaja sobre una fecha explícita. */
    static LocalDate toBusinessDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
