package com.fintech.charges.infrastructure.adapter.in.api;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.DaySnapshotStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La operación del cierre por cuenta: cómo va un día, qué cuentas están en problemas y por qué, y el
 * reintento de las que estaban en cuarentena una vez corregido su dato.
 */
@RestController
@RequestMapping("/api/v1/charges/day-close")
@Tag(name = "Day close", description = "Cierre de cargos por cuenta: estado, cuentas en problemas y reintentos")
class DayCloseController {

    private static final Logger log = LoggerFactory.getLogger(DayCloseController.class);
    private static final int HELD_SHOWN = 200;

    private final AccrualDayRepository days;
    private final AccrualUnitStore units;
    private final DaySnapshotStore snapshots;

    DayCloseController(AccrualDayRepository days, AccrualUnitStore units, DaySnapshotStore snapshots) {
        this.days      = days;
        this.units     = units;
        this.snapshots = snapshots;
    }

    /**
     * One account in one day's close, end to end: the portfolio's snapshot it was planned on, the unit
     * with the basis it accrued on, and the charges it produced. It is the audit trail behind a statement
     * line: {@code basis × rate / 360} must equal each interest charge.
     */
    @GetMapping("/{date}/accounts/{accountId}")
    @Operation(summary = "Una cuenta en el cierre de un día: la foto de cartera, su unidad con la base y los cargos que generó")
    ResponseEntity<Map<String, Object>> account(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                @PathVariable UUID accountId) {
        var unit = units.detail(date, accountId);
        if (unit.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessDate", date);
        body.put("creditAccountId", accountId);
        body.put("snapshot", snapshots.find(date, accountId).orElse(null));
        body.put("unit", unit.get());
        body.put("charges", days.accountCharges(date, accountId));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{date}")
    @Operation(summary = "Cómo va el cierre de cargos de un día: cuentas por estado, cifras y las que quedaron fuera")
    ResponseEntity<Map<String, Object>> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var dia = days.find(date);
        if (dia.isEmpty()) return ResponseEntity.notFound().build();
        var d = dia.get();
        var c = units.counts(date);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessDate", date);
        body.put("status", d.status());
        body.put("waitingFor", d.waitingFor());
        body.put("accounts", Map.of("planned", c.total(), "pending", c.pending(), "claimed", c.claimed(),
                "done", c.done(), "failed", c.failed(), "quarantined", c.quarantined(), "held", c.held()));
        body.put("controlTotals", d.totals());
        List<UUID> fuera = units.heldAccounts(date);
        body.put("outsideBatch", fuera.size() > HELD_SHOWN ? fuera.subList(0, HELD_SHOWN) : fuera);
        body.put("startedAt", d.startedAt());
        body.put("completedAt", d.completedAt());
        body.put("announcedAt", d.announcedAt());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/accounts-in-trouble")
    @Operation(summary = "Cuentas en cuarentena o retenidas, de todos los días, con su motivo")
    List<AccrualUnitStore.Trouble> inTrouble(@RequestParam(defaultValue = "200") int limit) {
        return units.inTrouble(limit);
    }

    @PostMapping("/{date}/accounts/{accountId}/retry")
    @Operation(summary = "Vuelve a intentar una cuenta en cuarentena (después de corregir su dato)")
    Map<String, Object> retryAccount(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @PathVariable UUID accountId) {
        int n = units.retryQuarantined(date, accountId);
        log.info("Reintento de la cuenta {} del cierre del {}: {}", accountId, date, n == 1 ? "a la cola" : "no estaba en cuarentena");
        return Map.of("businessDate", date, "creditAccountId", accountId, "requeued", n);
    }

    @PostMapping("/{date}/retry-quarantined")
    @Operation(summary = "Vuelve a intentar todas las cuentas en cuarentena de un día")
    Map<String, Object> retryAll(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        int n = units.retryQuarantined(date, null);
        log.info("Reintento de la cuarentena del cierre del {}: {} cuentas a la cola", date, n);
        return Map.of("businessDate", date, "requeued", n);
    }
}
