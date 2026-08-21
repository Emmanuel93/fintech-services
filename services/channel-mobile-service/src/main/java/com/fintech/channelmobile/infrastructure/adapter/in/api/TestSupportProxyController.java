package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.out.client.TestSupportClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Reenvía al BFF los endpoints dev-only /internal/test-support/* de charges-service
 * y credit-portfolio-service (no alcanzables directo desde fuera de la red Docker).
 * Permite a la app/pruebas E2E disparar los jobs @Scheduled bajo demanda y simular
 * el paso del tiempo, respetando siempre app → gateway → BFF → backend.
 *
 * Deshabilitado por default — activo solo con fintech.channel-mobile.test-support-enabled=true.
 */
@RestController
@ConditionalOnProperty(name = "fintech.channel-mobile.test-support-enabled", havingValue = "true")
class TestSupportProxyController {

    private final TestSupportClient testSupportClient;

    TestSupportProxyController(TestSupportClient testSupportClient) {
        this.testSupportClient = testSupportClient;
    }

    @PostMapping("/internal/test-support/run-daily-accrual")
    ResponseEntity<Map<String, Object>> runDailyAccrual() {
        return ResponseEntity.ok(testSupportClient.runDailyAccrual());
    }

    @PostMapping("/internal/test-support/run-upcoming-installment-job")
    ResponseEntity<Map<String, Object>> runUpcomingInstallmentJob() {
        return ResponseEntity.ok(testSupportClient.runUpcomingInstallmentJob());
    }

    @PostMapping("/internal/test-support/run-installment-due-job")
    ResponseEntity<Map<String, Object>> runInstallmentDueJob() {
        return ResponseEntity.ok(testSupportClient.runInstallmentDueJob());
    }

    @PostMapping("/internal/test-support/run-delinquency-job")
    ResponseEntity<Map<String, Object>> runDelinquencyJob() {
        return ResponseEntity.ok(testSupportClient.runDelinquencyJob());
    }

    @PostMapping("/internal/test-support/accounts/{creditAccountId}/installments/{installmentNumber}/shift-due-date")
    ResponseEntity<Map<String, Object>> shiftInstallmentDueDate(
            @PathVariable String creditAccountId,
            @PathVariable int installmentNumber,
            @RequestParam int daysFromToday) {
        return ResponseEntity.ok(testSupportClient.shiftInstallmentDueDate(
                creditAccountId, installmentNumber, daysFromToday));
    }
}
