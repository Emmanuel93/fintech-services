package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import com.fintech.stp.infrastructure.adapter.out.http.stub.StubScenario;
import com.fintech.stp.infrastructure.adapter.out.http.stub.StubScenarioAmounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only: runs the settlement poller on demand. A simulated month lasts minutes, and the poller's three-minute
 * interval would leave the last orders of the month unobserved when the month-end close reconciles banks against
 * treasury's cash book; in production that close runs hours after the last order settled.
 *
 * <p>Exists only with {@code fintech.test-support.enabled=true}; the gateway never routes {@code /internal/*}. The
 * poller's grace still applies: an order sent seconds ago is not asked about yet.
 *
 * <p>It also arms the stub's scenarios: with {@code scenario-amounts: ARMED} only the amounts an end-to-end run arms
 * here play the scenario their cents name, and every other order settles.
 */
@RestController
@RequestMapping("/internal/test-support")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
public class TestSupportController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportController.class);

    private final PollSettlementsUseCase pollSettlements;
    private final ObjectProvider<StubScenarioAmounts> stubScenarioAmounts;
    private final StpProperties properties;

    public TestSupportController(PollSettlementsUseCase pollSettlements,
                                 ObjectProvider<StubScenarioAmounts> stubScenarioAmounts, StpProperties properties) {
        this.pollSettlements = pollSettlements;
        this.stubScenarioAmounts = stubScenarioAmounts;
        this.properties = properties;
    }

    /** The amounts to arm. */
    record ScenarioAmounts(List<BigDecimal> amounts) {}

    /**
     * Replaces the stub's armed amounts; each comes back with the scenario its cents name. The selection says whether
     * the stub reads only them ({@code ARMED}) or every amount ({@code ANY}): a run that relies on the first can stop
     * when it gets the second.
     */
    @PutMapping("/stub/scenario-amounts")
    ResponseEntity<Map<String, Object>> armScenarioAmounts(@RequestBody ScenarioAmounts body) {
        StubScenarioAmounts amounts = stubScenarioAmounts.getIfAvailable();
        if (amounts == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "El conector no usa el doble de STP: no hay escenarios que armar"));
        }
        List<BigDecimal> armed = amounts.arm(body.amounts() == null ? List.of() : body.amounts());
        log.info("[test-support] Importes armados para los escenarios del stub: {}", armed);
        return ResponseEntity.ok(described(armed));
    }

    @GetMapping("/stub/scenario-amounts")
    ResponseEntity<Map<String, Object>> scenarioAmounts() {
        StubScenarioAmounts amounts = stubScenarioAmounts.getIfAvailable();
        if (amounts == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "El conector no usa el doble de STP"));
        }
        return ResponseEntity.ok(described(amounts.armed()));
    }

    private Map<String, Object> described(List<BigDecimal> armed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selection", properties.getGateway().getStub().getScenarioAmounts().name());
        out.put("armed", armed.stream()
                .map(a -> Map.of("amount", a, "scenario", StubScenario.forAmount(a).name())).toList());
        return out;
    }

    @PostMapping("/run-settlement-poll")
    ResponseEntity<Map<String, Integer>> runSettlementPoll() {
        int applied = pollSettlements.pollInFlightOrders();
        log.info("[test-support] Consulta de liquidaciones: {} observaciones aplicadas", applied);
        return ResponseEntity.ok(Map.of("appliedObservations", applied));
    }
}
