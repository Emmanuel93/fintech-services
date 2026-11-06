package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.ReceiveInboundCreditUseCase;
import com.fintech.stp.application.port.in.ReceiveInboundCreditUseCase.CreditReceipt;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.collection.InboundCreditFacts;
import com.fintech.stp.domain.collection.ReturnCause;
import com.fintech.stp.infrastructure.adapter.in.api.dto.StpCreditNotification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * The webhook STP calls when a credit arrives on one of our CLABEs ({@code abono}). STP waits for the answer:
 * {@code 200 {"mensaje":"confirmar"}} keeps the money; {@code 400 {"mensaje":"devolver","id":<causa>}} makes STP send
 * it back to the payer at once, with Banxico's return cause.
 *
 * <p>The decision is recorded before answering, so a retry of the same credit gets the same answer.
 */
@RestController
@RequestMapping("/api/v1/stp/abonos")
@Tag(name = "STP Collection", description = "Abonos SPEI recibidos en las CLABEs de cobro")
class StpCreditWebhookController {

    private static final DateTimeFormatter STP_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ReceiveInboundCreditUseCase receiveCredit;
    private final StpCompanyRepository companies;
    private final BusinessClock clock;
    private final StpProperties properties;

    StpCreditWebhookController(ReceiveInboundCreditUseCase receiveCredit, StpCompanyRepository companies,
                               BusinessClock clock, StpProperties properties) {
        this.receiveCredit = receiveCredit;
        this.companies = companies;
        this.clock = clock;
        this.properties = properties;
    }

    @PostMapping
    @Operation(summary = "Abono SPEI recibido (webhook de STP): se confirma o se devuelve")
    ResponseEntity<Map<String, Object>> receive(@RequestBody StpCreditNotification notification,
                                                @RequestHeader(value = "X-Webhook-Token", required = false) String token) {
        if (!authorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("mensaje", "no autorizado"));
        }
        Optional<StpCompany> company = companies.findByCode(notification.empresa());
        if (company.isEmpty()) {
            return returned(ReturnCause.CUENTA_INEXISTENTE);
        }
        CreditReceipt receipt = receiveCredit.receive(facts(notification, company.get()));
        return receipt.confirmed()
                ? ResponseEntity.ok(Map.of("mensaje", "confirmar"))
                : returned(receipt.cause());
    }

    private InboundCreditFacts facts(StpCreditNotification n, StpCompany company) {
        LocalDate operationDate = n.fechaOperacion() == null ? null
                : LocalDate.parse(String.valueOf(n.fechaOperacion()), STP_DATE);
        return new InboundCreditFacts(company.getCompanyId(), n.id() == null ? 0L : n.id(), n.claveRastreo(),
                operationDate, clock.now(), n.monto(), n.cuentaBeneficiario(), n.nombreBeneficiario(),
                n.nombreOrdenante(), n.cuentaOrdenante(), n.institucionOrdenante(), n.rfcCurpOrdenante(),
                n.tipoPago(), n.conceptoPago(), n.referenciaNumerica(), n.tsLiquidacion());
    }

    private static ResponseEntity<Map<String, Object>> returned(ReturnCause cause) {
        ReturnCause c = cause != null ? cause : ReturnCause.FALTA_INFORMACION;
        return ResponseEntity.badRequest().body(Map.of("mensaje", "devolver", "id", c.code()));
    }

    /** Constant-time comparison against the configured secret; no secret configured means the local stack. */
    private boolean authorized(String token) {
        String expected = properties.getCollection().getWebhookToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return token != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
