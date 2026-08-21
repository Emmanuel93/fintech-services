package com.fintech.stp.application.service;

import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.out.OrderingAccountRepository;
import com.fintech.stp.application.port.out.OutboxMessageRepository;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderEventRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.application.port.out.TrackingKeySequencePort;
import com.fintech.stp.domain.ClabeValidator;
import com.fintech.stp.domain.CompanyNotFoundException;
import com.fintech.stp.domain.InvalidBeneficiaryAccountException;
import com.fintech.stp.domain.OrderingAccount;
import com.fintech.stp.domain.OrderingAccountNotFoundException;
import com.fintech.stp.domain.OutboxMessage;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderEvent;
import com.fintech.stp.domain.StpPaymentOrderStatus;
import com.fintech.stp.domain.TrackingKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Registra la orden y encola su envío. <strong>No habla con STP.</strong>
 *
 * <p>Todo lo que hace ocurre dentro de una transacción de base de datos que termina con un commit
 * y un mensaje en el outbox. La llamada de red la hace después {@code OutboxRelayService}, fuera de
 * cualquier transacción. Ése es el punto: el legado hacía {@code save()} y la llamada HTTP juntos,
 * y cuando el webhook posterior fallaba reprocesaba todo — incluido un segundo PUT a STP.
 */
@Service
@Transactional
public class PaymentOrderRegistrationService implements RegisterPaymentOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderRegistrationService.class);

    private final StpPaymentOrderRepository orderRepository;
    private final StpPaymentOrderEventRepository eventRepository;
    private final StpCompanyRepository companyRepository;
    private final OrderingAccountRepository orderingAccountRepository;
    private final TrackingKeySequencePort trackingKeySequence;
    private final OutboxMessageRepository outboxRepository;
    private final AccountHasher accountHasher;
    private final Clock clock;

    public PaymentOrderRegistrationService(StpPaymentOrderRepository orderRepository,
                                           StpPaymentOrderEventRepository eventRepository,
                                           StpCompanyRepository companyRepository,
                                           OrderingAccountRepository orderingAccountRepository,
                                           TrackingKeySequencePort trackingKeySequence,
                                           OutboxMessageRepository outboxRepository,
                                           AccountHasher accountHasher,
                                           Clock clock) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.orderingAccountRepository = orderingAccountRepository;
        this.trackingKeySequence = trackingKeySequence;
        this.outboxRepository = outboxRepository;
        this.accountHasher = accountHasher;
        this.clock = clock;
    }

    @Override
    public void register(RegisterPaymentOrderCommand cmd) {
        // Nivel 1 de idempotencia: guard explícito. El nivel 2 es el UNIQUE de la tabla, que
        // arbitra las carreras entre réplicas.
        if (orderRepository.existsByPaymentRequestId(cmd.paymentRequestId())) {
            log.info("Duplicate payment request {} — skipping (idempotent)", cmd.paymentRequestId());
            return;
        }

        StpCompany company = companyRepository.findById(cmd.companyId())
                .filter(StpCompany::isActive)
                .orElseThrow(() -> new CompanyNotFoundException(
                        "No hay empresa activa con companyId=" + cmd.companyId()));

        OrderingAccount orderingAccount = orderingAccountRepository.findDefaultByCompanyId(company.getCompanyId())
                .orElseThrow(() -> new OrderingAccountNotFoundException(
                        "La empresa " + company.getCode() + " no tiene cuenta ordenante activa por default"));

        // Validación local: una CLABE con dígito verificador malo no merece un viaje a STP.
        if (isClabe(accountTypeOf(cmd)) && !ClabeValidator.isValid(cmd.beneficiaryAccount())) {
            throw new InvalidBeneficiaryAccountException(
                    "La CLABE del beneficiario no es válida (dígito verificador incorrecto): "
                            + AccountHasher.mask(cmd.beneficiaryAccount()));
        }

        LocalDate businessDate = LocalDate.now(clock);
        TrackingKey trackingKey = TrackingKey.generate(
                company.getTrackingPrefix(),
                businessDate,
                trackingKeySequence.next(company.getCompanyId(), businessDate));

        StpPaymentOrder order = StpPaymentOrder.create(
                cmd.paymentRequestId(), company.getCompanyId(), orderingAccount.getOrderingAccountId(),
                trackingKey, businessDate, cmd.amount(),
                cmd.beneficiaryName(), cmd.beneficiaryAccount(),
                accountHasher.hash(cmd.beneficiaryAccount()), accountTypeOf(cmd),
                cmd.beneficiaryTaxId(), resolveInstitution(cmd),
                cmd.concept(), cmd.numericReference(), cmd.paymentType(), cmd.correlationId());

        orderRepository.save(order);
        eventRepository.save(StpPaymentOrderEvent.of(order.getStpPaymentOrderId(), null,
                StpPaymentOrderStatus.PENDING, null, "Orden registrada", "SYSTEM"));

        // El payload del outbox es sólo el id: el relay relee la orden. Así no hay dos copias de
        // la verdad y una migración de esquema no invalida lo que quedó encolado.
        outboxRepository.save(OutboxMessage.registerOrder(
                order.getStpPaymentOrderId(), order.getStpPaymentOrderId().toString()));

        log.info("STP payment order registered paymentRequestId={} companyId={} trackingKey={} amount={} account={}",
                cmd.paymentRequestId(), company.getCompanyId(), trackingKey.value(),
                cmd.amount(), AccountHasher.mask(cmd.beneficiaryAccount()));
    }

    /**
     * La institución contraparte son los tres primeros dígitos de la CLABE cuando el emisor no la
     * manda. Es un dato derivable, no hay razón para exigirlo.
     */
    private Integer resolveInstitution(RegisterPaymentOrderCommand cmd) {
        if (cmd.beneficiaryInstitution() != null) {
            return cmd.beneficiaryInstitution();
        }
        String account = cmd.beneficiaryAccount();
        if (isClabe(accountTypeOf(cmd)) && account != null && account.length() >= 3) {
            return Integer.valueOf(account.substring(0, 3));
        }
        throw new InvalidBeneficiaryAccountException(
                "No se pudo determinar la institución contraparte y el emisor no la envió");
    }

    private static boolean isClabe(String accountType) {
        return accountType == null || "40".equals(accountType);
    }

    /**
     * Tipo de cuenta con default explícito.
     *
     * <p>La columna es {@code NOT NULL} y el productor puede no mandarlo: sin este default, un
     * mensaje perfectamente válido reventaba al hacer commit y acababa en el DLT. "40" es CLABE, que
     * es el caso por defecto en SPEI.
     */
    private static String accountTypeOf(RegisterPaymentOrderCommand cmd) {
        return cmd.beneficiaryAccountType() != null && !cmd.beneficiaryAccountType().isBlank()
                ? cmd.beneficiaryAccountType()
                : "40";
    }
}
