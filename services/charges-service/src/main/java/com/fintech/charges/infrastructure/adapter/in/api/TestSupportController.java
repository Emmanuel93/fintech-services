package com.fintech.charges.infrastructure.adapter.in.api;

import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.service.InterestAccrualService;
import com.fintech.charges.infrastructure.job.DailyAccrualJob;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob;
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

    private final InterestAccrualService accrualService;
    private final DailyAccrualJob dailyAccrualJob;
    private final MoratoriumAccrualJob moratoriumAccrualJob;
    private final DayCloseAccrualJob dayCloseAccrualJob;
    private final AccrualUnitStore units;

    TestSupportController(InterestAccrualService accrualService,
                          DailyAccrualJob dailyAccrualJob,
                          MoratoriumAccrualJob moratoriumAccrualJob,
                          DayCloseAccrualJob dayCloseAccrualJob,
                          AccrualUnitStore units) {
        this.accrualService = accrualService;
        this.dailyAccrualJob = dailyAccrualJob;
        this.moratoriumAccrualJob = moratoriumAccrualJob;
        this.dayCloseAccrualJob = dayCloseAccrualJob;
        this.units = units;
    }

    /**
     * La noche de un día, como la corre el cron desde las 00:05: pasa por las mismas compuertas
     * (el día anterior cerrado aquí y en cartera, cargos al día con lo que publicó cartera) y, si
     * abren, devenga ordinario y moratorio y avisa con las cifras de control.
     *
     * <p>Para correr el reloj noche por noche se llama día tras día: cada llamada espera, hasta
     * {@code gate-wait}, a que cartera cierre el día anterior.
     *
     * @param date el día que se cierra; por omisión, ayer.
     */
    @PostMapping("/run-day-close")
    ResponseEntity<Map<String, Object>> runDayClose(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : BusinessClock.current().today().minusDays(1);
        log.info("[test-support] Cerrando los cargos del día {}", target);
        var r = dayCloseAccrualJob.closeDay(target, "test-support");
        return ResponseEntity.ok(Map.of("businessDate", target.toString(), "outcome", r.outcome().name(),
                "detail", r.detail()));
    }

    /** Reintenta la cuarentena de un día —una cuenta o todas—, como el endpoint de operación pero sin token. */
    @PostMapping("/retry-quarantined")
    ResponseEntity<Map<String, Object>> retryQuarantined(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) java.util.UUID account) {
        int n = units.retryQuarantined(date, account);
        return ResponseEntity.ok(Map.of("businessDate", date.toString(), "requeued", n));
    }

    /** Reenvía los cargos del devengo de un día con su mismo id. Cartera aplica sólo los que no tenía. */
    @PostMapping("/republish-day")
    ResponseEntity<Map<String, Object>> republishDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int n = dayCloseAccrualJob.republish(date);
        return ResponseEntity.ok(Map.of("businessDate", date.toString(), "republished", n));
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
        LocalDate target = date != null ? date : BusinessClock.current().today();
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
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID creditAccountId) {
        // Scoped to one account when given: the E2E used to rewind the whole portfolio and re-accrue
        // days every other account already had, which is what produced the phantom charges.
        var schedules = accrualService.rewindTo(date, creditAccountId);
        log.info("[test-support] {} calendarios de devengo retrocedidos a {}{}", schedules, date,
                creditAccountId != null ? " (cuenta " + creditAccountId + ")" : "");
        return ResponseEntity.ok(Map.of("date", date.toString(), "schedulesRewound", schedules));
    }

    @PostMapping("/run-moratorium-accrual")
    ResponseEntity<Map<String, Object>> runMoratoriumAccrual(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : BusinessClock.current().today();
        int eligible = accrualService.findMoratoriumScheduleIds().size();
        log.info("[test-support] Disparando MoratoriumAccrualJob manualmente, date={} eligible={}", target, eligible);
        moratoriumAccrualJob.runMoratoriumAccrualFor(target);
        return ResponseEntity.ok(Map.of("date", target.toString(), "eligibleSchedules", eligible));
    }
}
