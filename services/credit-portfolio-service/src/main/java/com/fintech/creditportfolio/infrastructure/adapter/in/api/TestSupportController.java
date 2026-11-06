package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.infrastructure.config.CreditPortfolioProperties;
import com.fintech.creditportfolio.infrastructure.job.DayCloseRunner;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyCalculationJob;
import com.fintech.creditportfolio.infrastructure.job.InstallmentDueJob;
import com.fintech.creditportfolio.infrastructure.job.UpcomingInstallmentJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Dev-only: dispara bajo demanda los mismos beans @Scheduled que corren a medianoche,
 * para pruebas E2E que necesitan simular el paso de varios días sin esperar el cron real.
 * Deshabilitado por default — solo activo con fintech.test-support.enabled=true.
 */
@RestController
@RequestMapping("/internal/test-support")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class TestSupportController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportController.class);

    private final InstallmentRepository installmentRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditPortfolioProperties properties;
    private final UpcomingInstallmentJob upcomingInstallmentJob;
    private final InstallmentDueJob installmentDueJob;
    private final DelinquencyCalculationJob delinquencyCalculationJob;
    private final DayCloseRunner dayCloseRunner;
    private final BalanceReconciliationService reconciliation;

    TestSupportController(InstallmentRepository installmentRepository,
                          CreditAccountRepository creditAccountRepository,
                          CreditPortfolioProperties properties,
                          UpcomingInstallmentJob upcomingInstallmentJob,
                          InstallmentDueJob installmentDueJob,
                          DelinquencyCalculationJob delinquencyCalculationJob,
                          DayCloseRunner dayCloseRunner,
                          BalanceReconciliationService reconciliation) {
        this.dayCloseRunner = dayCloseRunner;
        this.reconciliation = reconciliation;
        this.installmentRepository = installmentRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.properties = properties;
        this.upcomingInstallmentJob = upcomingInstallmentJob;
        this.installmentDueJob = installmentDueJob;
        this.delinquencyCalculationJob = delinquencyCalculationJob;
    }

    /**
     * Applies a payment as if payments-service had published it, so a lab can pay accounts on a shifted
     * business date without the whole payments chain. Same code path as the Kafka listener.
     */
    @PostMapping("/accounts/{creditAccountId}/payments")
    ResponseEntity<Map<String, Object>> applyPayment(@PathVariable UUID creditAccountId,
                                                     @RequestParam BigDecimal amount,
                                                     @RequestParam(required = false) String reference) {
        String sourceEventId = reference != null ? reference : "test-support:" + UUID.randomUUID();
        log.info("[test-support] Pago de {} a la cuenta {} ({})", amount, creditAccountId, sourceEventId);
        reconciliation.onPaymentApplied(sourceEventId, creditAccountId, amount);
        var account = creditAccountRepository.findById(creditAccountId).orElseThrow();
        return ResponseEntity.ok(Map.of("creditAccountId", creditAccountId.toString(), "sourceEventId", sourceEventId,
                "principalBalance", account.getPrincipalBalance(), "totalDebt", account.getTotalDebt(),
                "valueDate", BusinessClock.current().today().toString()));
    }

    /**
     * La parte de cartera en el cierre de un día, por las mismas compuertas que el aviso de cargos: si
     * cargos no avisó el día, si falta el anterior o si los intereses no cuadran, responde
     * {@code WAITING} con el motivo. Un día ya cerrado sólo vuelve a avisar.
     *
     * @param date el día que se cierra; por omisión, ayer.
     */
    @PostMapping("/run-day-close")
    ResponseEntity<Map<String, Object>> runDayClose(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : BusinessClock.current().today().minusDays(1);
        log.info("[test-support] Cerrando el día {} en cartera", target);
        var r = dayCloseRunner.close(target, "test-support");
        return ResponseEntity.ok(Map.of("businessDate", target.toString(),
                "delinquencyAsOf", target.plusDays(1).toString(), "outcome", r.outcome().name(),
                "detail", r.detail()));
    }

    @PostMapping("/run-upcoming-installment-job")
    ResponseEntity<Map<String, Object>> runUpcomingInstallmentJob() {
        LocalDate target = BusinessClock.current().today().plusDays(properties.getReminderLeadDays());
        int eligible = installmentRepository.findDueOn(target).size();
        log.info("[test-support] Disparando UpcomingInstallmentJob manualmente, targetDate={} eligible={}",
                target, eligible);
        upcomingInstallmentJob.runFor(BusinessClock.current().today());
        return ResponseEntity.ok(Map.of("targetDate", target.toString(), "eligibleInstallments", eligible));
    }

    @PostMapping("/run-installment-due-job")
    ResponseEntity<Map<String, Object>> runInstallmentDueJob() {
        LocalDate today = BusinessClock.current().today();
        // El mismo criterio que usa el job: si aquí se contara sólo "vence hoy",
        // la respuesta diría 0 mientras el job procesa las ya vencidas.
        int eligible = installmentRepository.findDueOnOrBefore(today).size();
        log.info("[test-support] Disparando InstallmentDueJob manualmente, date={} eligible={}", today, eligible);
        installmentDueJob.runFor(today);
        return ResponseEntity.ok(Map.of("date", today.toString(), "eligibleInstallments", eligible));
    }

    @PostMapping("/accounts/{creditAccountId}/installments/{installmentNumber}/shift-due-date")
    @Transactional
    ResponseEntity<Map<String, Object>> shiftInstallmentDueDate(
            @PathVariable UUID creditAccountId,
            @PathVariable int installmentNumber,
            @RequestParam int daysFromToday) {
        LocalDate newDueDate = BusinessClock.current().today().plusDays(daysFromToday);
        installmentRepository.shiftDueDate(creditAccountId, installmentNumber, newDueDate);
        log.info("[test-support] installment {}#{} due_date -> {}",
                creditAccountId, installmentNumber, newDueDate);
        return ResponseEntity.ok(Map.of(
                "creditAccountId", creditAccountId.toString(),
                "installmentNumber", installmentNumber,
                "newDueDate", newDueDate.toString()));
    }

    /**
     * Envejece el calendario de una disposición: le corre todas las cuotas {@code daysAgo} días
     * hacia atrás.
     *
     * <p>Es lo que permite sembrar una línea revolvente en mora sin esperar meses reales. Con
     * {@code daysAgo=95} la primera cuota de esa colocación venció hace unos 65 días, y el
     * envejecido calcula el DPD **contándolo**, no leyéndolo de ningún lado: la mora sigue saliendo
     * de la regla que la produce, que es lo único que hace que el dato valga.
     */
    @PostMapping("/dispositions/{dispositionId}/age-schedule")
    @Transactional
    ResponseEntity<Map<String, Object>> ageDispositionSchedule(@PathVariable UUID dispositionId,
                                                                @RequestParam int daysAgo) {
        var installments = installmentRepository.findByScheduleIdOrdered(dispositionId);
        for (var installment : installments) {
            installmentRepository.shiftDueDate(dispositionId, installment.getInstallmentNumber(),
                    installment.getDueDate().minusDays(daysAgo));
        }
        log.info("[test-support] calendario de la disposición {} envejecido {} días ({} cuotas)",
                dispositionId, daysAgo, installments.size());
        return ResponseEntity.ok(Map.of(
                "dispositionId", dispositionId.toString(),
                "daysAgo", daysAgo,
                "installmentsShifted", installments.size()));
    }

    @PostMapping("/run-delinquency-job")
    ResponseEntity<Map<String, Object>> runDelinquencyJob() {
        LocalDate today = BusinessClock.current().today();
        int eligible = creditAccountRepository.findAllByStatus(CreditAccountStatus.ACTIVE).size();
        log.info("[test-support] Disparando DelinquencyCalculationJob manualmente, date={} eligible={}", today, eligible);
        delinquencyCalculationJob.runFor(today);
        return ResponseEntity.ok(Map.of("date", today.toString(), "eligibleAccounts", eligible));
    }
}
