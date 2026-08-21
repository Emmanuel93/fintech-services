package com.fintech.charges.infrastructure.adapter.in.api;

import com.fintech.charges.application.service.InterestAccrualService;
import com.fintech.charges.infrastructure.job.DailyAccrualJob;
import com.fintech.charges.infrastructure.job.MoratoriumAccrualJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

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

    private final InterestAccrualService accrualService;
    private final DailyAccrualJob dailyAccrualJob;
    private final MoratoriumAccrualJob moratoriumAccrualJob;

    TestSupportController(InterestAccrualService accrualService,
                          DailyAccrualJob dailyAccrualJob,
                          MoratoriumAccrualJob moratoriumAccrualJob) {
        this.accrualService = accrualService;
        this.dailyAccrualJob = dailyAccrualJob;
        this.moratoriumAccrualJob = moratoriumAccrualJob;
    }

    /**
     * @param date día a devengar; por omisión, hoy.
     *
     * <p>Existe porque el devengo es idempotente por día: llamar treinta veces a este endpoint sin
     * fecha produce <b>un</b> día de interés, no treinta. Para sembrar meses de historia hay que
     * pasar la fecha e ir corriendo el reloj.
     */
    @PostMapping("/run-daily-accrual")
    ResponseEntity<Map<String, Object>> runDailyAccrual(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        int eligible = accrualService.findScheduleIdsForAccrual(target).size();
        log.info("[test-support] Disparando DailyAccrualJob manualmente, date={} eligible={}", target, eligible);
        dailyAccrualJob.runDailyAccrualFor(target);
        return ResponseEntity.ok(Map.of("date", target.toString(), "eligibleSchedules", eligible));
    }

    /**
     * Retrocede el reloj del devengo de todos los calendarios activos, para poder sembrar historia.
     *
     * <p>Con esto y {@code run-daily-accrual?date=}, la siembra puede correr el reloj de junio a hoy
     * y dejar intereses repartidos por período en vez de un solo día apilado en el mes corriente.
     */
    @PostMapping("/rewind-accrual-schedules")
    ResponseEntity<Map<String, Object>> rewindAccrualSchedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var schedules = accrualService.rewindAllTo(date);
        log.info("[test-support] {} calendarios de devengo retrocedidos a {}", schedules, date);
        return ResponseEntity.ok(Map.of("date", date.toString(), "schedulesRewound", schedules));
    }

    @PostMapping("/run-moratorium-accrual")
    ResponseEntity<Map<String, Object>> runMoratoriumAccrual(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        int eligible = accrualService.findMoratoriumScheduleIds().size();
        log.info("[test-support] Disparando MoratoriumAccrualJob manualmente, date={} eligible={}", target, eligible);
        moratoriumAccrualJob.runMoratoriumAccrualFor(target);
        return ResponseEntity.ok(Map.of("date", target.toString(), "eligibleSchedules", eligible));
    }
}
