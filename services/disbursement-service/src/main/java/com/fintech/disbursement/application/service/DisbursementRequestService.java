package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.application.port.in.RequestDisbursementUseCase;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.Beneficiary;
import com.fintech.disbursement.domain.ClabeValidator;
import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.InvalidBeneficiaryAccountException;
import com.fintech.disbursement.domain.InvalidDisbursementInstructionException;
import com.fintech.disbursement.domain.Rail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Registra la orden. <strong>No habla con ningún proveedor.</strong>
 *
 * <p>Ese es el punto: el legado llamaba a STP por HTTP dentro de la transacción que ajustaba saldos,
 * así que un timeout dejaba el saldo aplicado sin saber si la orden existía. Aquí la transacción
 * sólo escribe en la base de datos; el despacho es de {@link DisbursementDispatchService}, fuera.
 */
@Service
public class DisbursementRequestService implements RequestDisbursementUseCase {

    private static final Logger log = LoggerFactory.getLogger(DisbursementRequestService.class);

    // Topes del DDL. Se comprueban aquí porque la ruta de Kafka no pasa por Bean Validation.
    private static final int MAX_SOURCE_EVENT_ID = 120;
    private static final int MAX_SOURCE_REFERENCE = 120;
    private static final int MAX_BENEFICIARY_NAME = 150;
    private static final int MAX_BENEFICIARY_ACCOUNT = 20;
    private static final int MAX_BENEFICIARY_TAX_ID = 18;
    private static final int MAX_ACCOUNT_TYPE = 4;
    private static final int MAX_CONCEPT = 40;
    private static final int CURRENCY_LENGTH = 3;

    private final DisbursementOrderRepository orders;
    private final DisbursementEventRepository events;
    private final CompanyResolutionService companyResolution;
    private final RoutingService routing;
    private final TransactionTemplate transactionTemplate;

    public DisbursementRequestService(DisbursementOrderRepository orders,
                                      DisbursementEventRepository events,
                                      CompanyResolutionService companyResolution,
                                      RoutingService routing,
                                      TransactionTemplate transactionTemplate) {
        this.orders = orders;
        this.events = events;
        this.companyResolution = companyResolution;
        this.routing = routing;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * <p>La transacción se abre con {@link TransactionTemplate} y no con {@code @Transactional} por
     * una razón concreta: la violación de la restricción única de idempotencia se levanta <em>en el
     * commit</em>, así que con la anotación ocurriría fuera del método y sería imposible manejarla
     * aquí. Con la plantilla, el commit queda dentro del {@code try}.
     */
    @Override
    public DisbursementOrder request(RequestDisbursementCommand command) {
        try {
            return transactionTemplate.execute(status -> doRequest(command));
        } catch (DataIntegrityViolationException e) {
            // DB-02 — dos réplicas procesaron el mismo evento a la vez y la base arbitró. Que gane
            // una es el comportamiento correcto: la otra devuelve la orden que ya existe en vez de
            // mandar un desembolso legítimo al DLT.
            log.info("Carrera de idempotencia resuelta por la base sourceSystem={} sourceEventId={}",
                    command.sourceSystem(), command.sourceEventId());
            return transactionTemplate.execute(status -> orders.findBySourceKey(
                            command.sourceSystem(), command.sourceType().name(), command.sourceEventId())
                    .orElseThrow(() -> e));
        }
    }

    private DisbursementOrder doRequest(RequestDisbursementCommand command) {
        var existing = orders.findBySourceKey(
                command.sourceSystem(), command.sourceType().name(), command.sourceEventId());
        if (existing.isPresent()) {
            log.info("Orden de desembolso ya existente sourceSystem={} sourceEventId={} disbursementId={}",
                    command.sourceSystem(), command.sourceEventId(), existing.get().getDisbursementId());
            return existing.get();
        }

        validate(command);

        UUID companyId = companyResolution.resolve(command);
        Rail rail = command.railOrDefault();
        Beneficiary beneficiary = buildBeneficiary(command, rail);

        DisbursementOrder order = DisbursementOrder.request(
                companyId, command.sourceSystem(), command.sourceType(), command.sourceReference(),
                command.sourceEventId(), command.metadataOrEmpty(), beneficiary, command.amount(),
                command.currencyOrDefault(), command.concept(), command.numericReference(), rail,
                command.correlationId());

        // DB-05 — fuera de ventana operativa la orden espera; no se rechaza.
        if (!routing.isOpen(rail, order.getCreatedAt())) {
            order.scheduleFor(routing.nextOpening(rail, order.getCreatedAt()));
            log.info("Fuera de ventana {}: disbursementId={} programado para {}",
                    rail, order.getDisbursementId(), order.getScheduledFor());
        }

        orders.save(order);
        events.save(DisbursementEvent.record(order, null, DisbursementStatus.REQUESTED,
                null, "origen=" + command.sourceSystem() + "/" + command.sourceType(), "SYSTEM"));

        log.info("Desembolso registrado disbursementId={} companyId={} rail={} monto={} origen={}",
                order.getDisbursementId(), companyId, rail, order.getAmount(), command.sourceSystem());
        return order;
    }

    /**
     * Comprueba lo que el DDL exige, <strong>antes</strong> de intentar escribirlo.
     *
     * <p>Hibernate no valida longitudes con {@code ddl-auto: validate}: un valor demasiado largo no
     * falla al recibirlo, falla al hacer commit. Por la ruta REST eso sería un 500; por la de Kafka,
     * cuatro reintentos y un desembolso real en el DLT.
     */
    private void validate(RequestDisbursementCommand command) {
        requireMax("sourceEventId", command.sourceEventId(), MAX_SOURCE_EVENT_ID);
        requireMax("sourceReference", command.sourceReference(), MAX_SOURCE_REFERENCE);
        requireMax("beneficiaryName", command.beneficiaryName(), MAX_BENEFICIARY_NAME);
        requireMax("beneficiaryAccount", command.beneficiaryAccount(), MAX_BENEFICIARY_ACCOUNT);
        requireMax("beneficiaryTaxId", command.beneficiaryTaxId(), MAX_BENEFICIARY_TAX_ID);
        requireMax("beneficiaryAccountType", command.beneficiaryAccountType(), MAX_ACCOUNT_TYPE);
        requireMax("concept", command.concept(), MAX_CONCEPT);

        if (command.sourceEventId() == null || command.sourceEventId().isBlank()) {
            throw new InvalidDisbursementInstructionException(
                    "sourceEventId es obligatorio: es la clave de idempotencia de la orden");
        }
        if (command.currencyOrDefault().length() != CURRENCY_LENGTH) {
            throw new InvalidDisbursementInstructionException(
                    "La moneda debe ser un código ISO de 3 letras, llegó: " + command.currencyOrDefault());
        }
    }

    private static void requireMax(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new InvalidDisbursementInstructionException(
                    field + " excede el máximo de " + max + " caracteres (llegaron " + value.length() + ")");
        }
    }

    /**
     * Validación <strong>local</strong> y sólo local: nada que dependa de configuración mutable o de
     * otro servicio. Rechazar aquí una CLABE con dígito verificador malo evita un viaje completo al
     * proveedor para obtener el mismo "no" (DB-04).
     */
    private Beneficiary buildBeneficiary(RequestDisbursementCommand command, Rail rail) {
        String account = command.beneficiaryAccount();
        String accountType = command.beneficiaryAccountType() != null
                ? command.beneficiaryAccountType() : "40";

        if (rail == Rail.SPEI && "40".equals(accountType) && !ClabeValidator.isValid(account)) {
            throw new InvalidBeneficiaryAccountException(
                    "CLABE inválida para desembolso SPEI: " + Beneficiary.mask(account));
        }

        Integer institution = command.beneficiaryInstitution() != null
                ? command.beneficiaryInstitution()
                : ClabeValidator.institutionOf(account);   // null si la cuenta no es numérica

        return Beneficiary.of(command.beneficiaryName(), account, accountType,
                command.beneficiaryTaxId(), institution);
    }
}
