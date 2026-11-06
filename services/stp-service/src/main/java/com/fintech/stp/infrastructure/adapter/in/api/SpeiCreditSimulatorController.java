package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.shared.banking.SpeiOperatingDay;
import com.fintech.shared.simulation.SimulatorGuard;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.ReceiveInboundCreditUseCase;
import com.fintech.stp.application.port.in.ReceiveInboundCreditUseCase.CreditReceipt;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.TrackingKeySequencePort;
import com.fintech.stp.domain.CompanyNotFoundException;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.collection.InboundCreditFacts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plays the payer's bank and STP for a credit to one of our CLABEs, in low environments only: a person pays by SPEI,
 * STP gives the transfer its id, its tracking key and its operation date (the cutoff rule at the simulated moment)
 * and notifies the connector, exactly through the port the webhook uses. The E2E pays its loans this way, so the money
 * in follows the production path: CLABE → inbox → decision → outbox → payments → portfolio.
 *
 * <p>Ids are deterministic (operation date + a per-day consecutive): two runs with the same seed produce the same
 * tracking keys. A request may repeat {@code stpId} and {@code trackingKey} to simulate STP retrying its webhook.
 */
@RestController
@RequestMapping("/internal/test-support/spei/abonos")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class SpeiCreditSimulatorController {

    /** The synthetic "payer bank" whose per-day consecutive numbers the simulated transfers. */
    static final UUID PAYER_BANK = UUID.fromString("00000000-0000-4000-8000-00000000b1c0");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final ReceiveInboundCreditUseCase receiveCredit;
    private final StpCompanyRepository companies;
    private final TrackingKeySequencePort sequence;
    private final SpeiOperatingDay operatingDay;
    private final BusinessClock clock;
    private final StpProperties properties;

    SpeiCreditSimulatorController(ReceiveInboundCreditUseCase receiveCredit, StpCompanyRepository companies,
                                  TrackingKeySequencePort sequence, SpeiOperatingDay operatingDay, BusinessClock clock,
                                  StpProperties properties, Environment environment) {
        SimulatorGuard.requireNonProduction(environment, "simulador de abonos SPEI", "TEST_SUPPORT_ENABLED=false");
        this.receiveCredit = receiveCredit;
        this.companies = companies;
        this.sequence = sequence;
        this.operatingDay = operatingDay;
        this.clock = clock;
        this.properties = properties;
    }

    /** A transfer from the payer's bank. Only {@code clabe} and {@code amount} are required. */
    record SimulatedCredit(String clabe, BigDecimal amount, String payerName, String payerAccount,
                           Integer payerInstitution, String payerTaxId, String concept, Long numericReference,
                           Integer paymentType, String trackingKey, Long stpId) {}

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> credit(@RequestBody SimulatedCredit request) {
        StpCompany company = companies.findByCode(properties.getCollection().getCompanyCode())
                .orElseThrow(() -> new CompanyNotFoundException("No existe la empresa de cobro"));
        Instant now = clock.now();
        LocalDate operationDate = operatingDay.operationDate(now);
        String trackingKey = request.trackingKey();
        Long stpId = request.stpId();
        if (trackingKey == null || stpId == null) {
            long n = sequence.next(PAYER_BANK, operationDate);
            trackingKey = trackingKey != null ? trackingKey : "SIMSPEI" + operationDate.format(DAY) + String.format("%06d", n);
            stpId = stpId != null ? stpId : Long.parseLong(operationDate.format(DAY)) * 1_000_000L + n;
        }
        InboundCreditFacts facts = new InboundCreditFacts(company.getCompanyId(), stpId, trackingKey, operationDate,
                now, request.amount(), request.clabe(), null, request.payerName(), request.payerAccount(),
                request.payerInstitution(), request.payerTaxId(),
                request.paymentType() != null ? request.paymentType() : 1, request.concept(),
                request.numericReference(), now.toEpochMilli());
        CreditReceipt receipt = receiveCredit.receive(facts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inboundCreditId", receipt.inboundCreditId());
        body.put("decision", receipt.decision().name());
        body.put("returnCause", receipt.cause() == null ? null : receipt.cause().code());
        body.put("duplicate", receipt.duplicate());
        body.put("trackingKey", trackingKey);
        body.put("stpId", stpId);
        body.put("operationDate", operationDate.toString());
        body.put("receivedAt", now.toString());
        return ResponseEntity.ok(body);
    }
}
