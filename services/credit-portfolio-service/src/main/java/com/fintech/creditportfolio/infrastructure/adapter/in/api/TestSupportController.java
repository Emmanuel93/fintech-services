package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.infrastructure.config.CreditPortfolioProperties;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

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

    TestSupportController(InstallmentRepository installmentRepository,
                          CreditAccountRepository creditAccountRepository,
                          CreditPortfolioProperties properties,
                          UpcomingInstallmentJob upcomingInstallmentJob,
                          InstallmentDueJob installmentDueJob,
                          DelinquencyCalculationJob delinquencyCalculationJob) {
        this.installmentRepository = installmentRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.properties = properties;
        this.upcomingInstallmentJob = upcomingInstallmentJob;
        this.installmentDueJob = installmentDueJob;
        this.delinquencyCalculationJob = delinquencyCalculationJob;
    }

    @PostMapping("/run-upcoming-installment-job")
    ResponseEntity<Map<String, Object>> runUpcomingInstallmentJob() {
        LocalDate target = LocalDate.now().plusDays(properties.getReminderLeadDays());
        int eligible = installmentRepository.findDueOn(target).size();
        log.info("[test-support] Disparando UpcomingInstallmentJob manualmente, targetDate={} eligible={}",
                target, eligible);
        upcomingInstallmentJob.run();
        return ResponseEntity.ok(Map.of("targetDate", target.toString(), "eligibleInstallments", eligible));
    }

    @PostMapping("/run-installment-due-job")
    ResponseEntity<Map<String, Object>> runInstallmentDueJob() {
        LocalDate today = LocalDate.now();
        // El mismo criterio que usa el job: si aquí se contara sólo "vence hoy",
        // la respuesta diría 0 mientras el job procesa las ya vencidas.
        int eligible = installmentRepository.findDueOnOrBefore(today).size();
        log.info("[test-support] Disparando InstallmentDueJob manualmente, date={} eligible={}", today, eligible);
        installmentDueJob.run();
        return ResponseEntity.ok(Map.of("date", today.toString(), "eligibleInstallments", eligible));
    }

    @PostMapping("/accounts/{creditAccountId}/installments/{installmentNumber}/shift-due-date")
    @Transactional
    ResponseEntity<Map<String, Object>> shiftInstallmentDueDate(
            @PathVariable UUID creditAccountId,
            @PathVariable int installmentNumber,
            @RequestParam int daysFromToday) {
        LocalDate newDueDate = LocalDate.now().plusDays(daysFromToday);
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
        var cuotas = installmentRepository.findByScheduleIdOrdered(dispositionId);
        for (var cuota : cuotas) {
            installmentRepository.shiftDueDate(dispositionId, cuota.getInstallmentNumber(),
                    cuota.getDueDate().minusDays(daysAgo));
        }
        log.info("[test-support] calendario de la disposición {} envejecido {} días ({} cuotas)",
                dispositionId, daysAgo, cuotas.size());
        return ResponseEntity.ok(Map.of(
                "dispositionId", dispositionId.toString(),
                "daysAgo", daysAgo,
                "installmentsShifted", cuotas.size()));
    }

    @PostMapping("/run-delinquency-job")
    ResponseEntity<Map<String, Object>> runDelinquencyJob() {
        LocalDate today = LocalDate.now();
        int eligible = creditAccountRepository.findAllByStatus(CreditAccountStatus.ACTIVE).size();
        log.info("[test-support] Disparando DelinquencyCalculationJob manualmente, date={} eligible={}", today, eligible);
        delinquencyCalculationJob.run();
        return ResponseEntity.ok(Map.of("date", today.toString(), "eligibleAccounts", eligible));
    }
}
