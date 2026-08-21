package com.fintech.stp.application.service;

import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.RelayOutboxUseCase;
import com.fintech.stp.application.port.out.OrderingAccountRepository;
import com.fintech.stp.application.port.out.OutboxMessageRepository;
import com.fintech.stp.application.port.out.SigningKeyProvider;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.application.port.out.StpGatewayPort;
import com.fintech.stp.application.port.out.StpPaymentOrderEventRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.OrderingAccount;
import com.fintech.stp.domain.OutboxMessage;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpCompanyKey;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderEvent;
import com.fintech.stp.domain.StpPaymentOrderStatus;
import com.fintech.stp.domain.signing.CadenaOriginalBuilder;
import com.fintech.stp.domain.signing.OrdenPagoFirma;
import com.fintech.stp.domain.signing.SignatureAlgorithm;
import com.fintech.stp.domain.signing.StpSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Toma lo pendiente del outbox, firma y lo envía a STP.
 *
 * <p>Es el único punto del servicio que hace una llamada saliente al registrar. La llamada ocurre
 * <strong>fuera</strong> de la transacción que persistió la orden: cada mensaje se toma con
 * {@code FOR UPDATE SKIP LOCKED} y se procesa en su propia transacción corta, de una en una.
 *
 * <p>Las transacciones se abren con {@link TransactionTemplate} y no con {@code @Transactional} por
 * dos razones concretas. Una: una anotación sobre un método privado llamado desde dentro de la misma
 * clase no pasa por el proxy y no hace nada — la versión anterior tenía un
 * {@code REQUIRES_NEW} decorativo. Dos: si un mensaje del lote marcaba la transacción como
 * rollback-only, el commit final revertía <em>todo</em> el lote, incluidas órdenes que STP ya había
 * aceptado y cuyos eventos ya estaban publicados.
 *
 * <p>Tomar el lock dentro de la misma transacción que hace la llamada HTTP es deliberado: si el
 * proceso muere a mitad, el lock se suelta y el mensaje sigue {@code PENDING}. Se bloquea una fila
 * durante una llamada, no cincuenta durante cincuenta.
 */
@Service
public class OutboxRelayService implements RelayOutboxUseCase {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private static final Integer TIPO_PAGO_TERCEROS = 1;

    private final OutboxMessageRepository outboxRepository;
    private final StpPaymentOrderRepository orderRepository;
    private final StpPaymentOrderEventRepository eventRepository;
    private final StpCompanyRepository companyRepository;
    private final OrderingAccountRepository orderingAccountRepository;
    private final SigningKeyProvider signingKeyProvider;
    private final StpGatewayPort gateway;
    private final StpEventPublisher eventPublisher;
    private final StpProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelayService(OutboxMessageRepository outboxRepository,
                              StpPaymentOrderRepository orderRepository,
                              StpPaymentOrderEventRepository eventRepository,
                              StpCompanyRepository companyRepository,
                              OrderingAccountRepository orderingAccountRepository,
                              SigningKeyProvider signingKeyProvider,
                              StpGatewayPort gateway,
                              StpEventPublisher eventPublisher,
                              StpProperties properties,
                              TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.orderingAccountRepository = orderingAccountRepository;
        this.signingKeyProvider = signingKeyProvider;
        this.gateway = gateway;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int relayPending() {
        int processed = 0;
        for (int i = 0; i < properties.getOutbox().getBatchSize(); i++) {
            Outcome outcome = relayOne();
            if (outcome == Outcome.EMPTY) {
                break;
            }
            if (outcome == Outcome.PROCESSED) {
                processed++;
            }
        }
        return processed;
    }

    private enum Outcome { PROCESSED, FAILED, EMPTY }

    /** Un mensaje, una transacción. El fallo se registra en otra, porque la primera ya revirtió. */
    private Outcome relayOne() {
        AtomicReference<UUID> claimed = new AtomicReference<>();
        try {
            Boolean any = transactionTemplate.execute(status -> {
                List<OutboxMessage> batch = outboxRepository.lockNextBatch(1);
                if (batch.isEmpty()) {
                    return Boolean.FALSE;
                }
                OutboxMessage message = batch.get(0);
                claimed.set(message.getOutboxId());
                dispatch(message);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(any) ? Outcome.PROCESSED : Outcome.EMPTY;
        } catch (RuntimeException e) {
            UUID outboxId = claimed.get();
            if (outboxId == null) {
                throw e;   // ni siquiera se pudo tomar el lote: que lo vea el job
            }
            transactionTemplate.executeWithoutResult(status ->
                    outboxRepository.findById(outboxId).ifPresent(message -> handleFailure(message, e)));
            return Outcome.FAILED;
        }
    }

    private void dispatch(OutboxMessage message) {
        UUID orderId = UUID.fromString(message.getPayload());
        StpPaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Outbox apunta a una orden inexistente: " + orderId));

        if (order.isTerminal()) {
            log.info("Order {} already terminal ({}) — dropping outbox message (idempotent)",
                    order.getTrackingKey(), order.getStatus());
            message.markSent();
            outboxRepository.save(message);
            return;
        }

        StpCompany company = companyRepository.findById(order.getCompanyId()).orElseThrow();
        OrderingAccount ordering = orderingAccountRepository.findById(order.getOrderingAccountId()).orElseThrow();
        StpCompanyKey keyMetadata = signingKeyProvider.activeKeyMetadata(
                company.getCompanyId(), com.fintech.stp.domain.KeyPurpose.SIGNING);

        OrdenPagoFirma firma = buildFirma(order, company, ordering);
        String cadenaOriginal = CadenaOriginalBuilder.build(firma);
        String sello = StpSigner.sign(cadenaOriginal,
                signingKeyProvider.activeSigningKey(company.getCompanyId()),
                SignatureAlgorithm.SHA256_WITH_RSA);

        // KY-03: se audita CON QUÉ se firmó, nunca QUÉ se firmó.
        log.info("Signing STP order trackingKey={} companyId={} keyId={} fingerprint={}",
                order.getTrackingKey(), company.getCompanyId(),
                keyMetadata.getKeyId(), keyMetadata.getFingerprintSha256());

        order.markSent(sello, keyMetadata.getKeyId());
        orderRepository.save(order);

        StpGatewayPort.RegistrationResult result = gateway.registerPaymentOrder(
                toRequest(order, company, ordering, firma, sello));

        if (BanxicoResponseCode.isAccepted(result.stpResponseId())) {
            accept(order, message, String.valueOf(result.stpResponseId()));
            return;
        }

        BanxicoResponseCode code = BanxicoResponseCode.of((int) result.stpResponseId());
        switch (code.terminality()) {
            case ALREADY_REGISTERED -> {
                // La orden ya existía de un intento anterior que no alcanzamos a persistir.
                // Tratarla como error crearía una segunda con clave nueva: doble dispersión.
                log.info("STP reports {} for trackingKey={} — treating as idempotent success",
                        code.name(), order.getTrackingKey());
                accept(order, message, order.getStpOrderId());
            }
            case RETRYABLE -> {
                // El corte por intentos también aplica aquí. Sin esto, un rechazo transitorio
                // sostenido —el enlace de STP en modo consultas, por ejemplo— reprogramaba el
                // mensaje para siempre: la orden nunca llegaba a FAILED y nadie la revisaba nunca.
                if (message.getAttemptCount() + 1 >= properties.getOutbox().getMaxAttempts()) {
                    StpPaymentOrderStatus from = order.status();
                    String detail = "Agotados los intentos con rechazo transitorio "
                            + code.name() + ": " + result.descripcionError();
                    order.fail(detail);
                    orderRepository.save(order);
                    eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                            StpPaymentOrderStatus.FAILED, code.name(), detail, "SYSTEM"));
                    message.markFailed(detail);
                    outboxRepository.save(message);
                    eventPublisher.publishOrderRejected(order, code, detail);
                    log.error("STP transient rejection {} exhausted for trackingKey={} — requiere revisión manual",
                            code.name(), order.getTrackingKey());
                    return;
                }
                order.scheduleRetry(code, result.descripcionError());
                orderRepository.save(order);
                message.scheduleRetry(code.name() + ": " + result.descripcionError());
                outboxRepository.save(message);
                log.warn("STP transient rejection {} for trackingKey={} — will retry (intento {})",
                        code.name(), order.getTrackingKey(), message.getAttemptCount());
            }
            case TERMINAL -> {
                StpPaymentOrderStatus from = order.status();
                order.reject(code, result.descripcionError());
                orderRepository.save(order);
                eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                        StpPaymentOrderStatus.REJECTED, code.name(), result.descripcionError(), "PROVIDER"));
                message.markSent();
                outboxRepository.save(message);
                eventPublisher.publishOrderRejected(order, code, result.descripcionError());
                log.warn("STP rejected trackingKey={} code={} reason={}",
                        order.getTrackingKey(), code.code(), code.name());
            }
        }
    }

    private void accept(StpPaymentOrder order, OutboxMessage message, String stpOrderId) {
        StpPaymentOrderStatus from = order.status();
        order.accept(stpOrderId);
        orderRepository.save(order);
        eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), from,
                StpPaymentOrderStatus.ACCEPTED, null, "STP aceptó el registro", "PROVIDER"));
        message.markSent();
        outboxRepository.save(message);
        eventPublisher.publishOrderAccepted(order);
        log.info("STP accepted trackingKey={} stpOrderId={}", order.getTrackingKey(), stpOrderId);
    }

    private void handleFailure(OutboxMessage message, RuntimeException error) {
        if (message.getAttemptCount() + 1 >= properties.getOutbox().getMaxAttempts()) {
            message.markFailed(error.getMessage());
            orderRepository.findById(message.getAggregateId()).ifPresent(order -> {
                if (!order.isTerminal()) {
                    order.fail("Agotados los intentos de envío: " + error.getMessage());
                    orderRepository.save(order);
                }
            });
            log.error("Outbox message {} exhausted after {} attempts: {}",
                    message.getOutboxId(), message.getAttemptCount(), error.getMessage());
        } else {
            message.scheduleRetry(error.getMessage());
            log.warn("Outbox message {} failed (attempt {}): {}",
                    message.getOutboxId(), message.getAttemptCount() + 1, error.getMessage());
        }
        outboxRepository.save(message);
    }

    private OrdenPagoFirma buildFirma(StpPaymentOrder order, StpCompany company, OrderingAccount ordering) {
        return OrdenPagoFirma.builder()
                .institucionContraparte(order.getBeneficiaryInstitution())
                .empresa(company.getStpEmpresa())
                .fechaOperacion(order.getBusinessDate())
                .claveRastreo(order.getTrackingKey())
                .institucionOperante(company.getInstitucionOperante())
                .monto(order.getAmount())
                .tipoPago(order.getPaymentType() != null
                        ? Integer.valueOf(order.getPaymentType()) : TIPO_PAGO_TERCEROS)
                .tipoCuentaOrdenante(ordering.accountTypeAsInt())
                .nombreOrdenante(ordering.getHolderName())
                .cuentaOrdenante(ordering.getClabe())
                .rfcCurpOrdenante(ordering.getTaxId())
                .tipoCuentaBeneficiario(Integer.valueOf(order.getBeneficiaryAccountType()))
                .nombreBeneficiario(order.getBeneficiaryNameSent())
                .cuentaBeneficiario(order.getBeneficiaryAccount())
                .rfcCurpBeneficiario(order.getBeneficiaryTaxId())
                .conceptoPago(order.getConcept())
                .referenciaNumerica(order.getNumericReference() != null
                        ? order.getNumericReference().intValue() : null)
                .build();
    }

    private StpGatewayPort.PaymentOrderRequest toRequest(StpPaymentOrder order, StpCompany company,
                                                          OrderingAccount ordering, OrdenPagoFirma firma,
                                                          String sello) {
        return new StpGatewayPort.PaymentOrderRequest(
                order.getTrackingKey(), company.getStpEmpresa(), order.getBusinessDate(),
                company.getInstitucionOperante(), order.getBeneficiaryInstitution(),
                order.getAmount(), firma.tipoPago(), ordering.accountTypeAsInt(),
                ordering.getHolderName(), ordering.getClabe(), ordering.getTaxId(),
                Integer.valueOf(order.getBeneficiaryAccountType()), order.getBeneficiaryNameSent(),
                order.getBeneficiaryAccount(), order.getBeneficiaryTaxId(),
                order.getConcept(), order.getNumericReference(), sello);
    }
}
