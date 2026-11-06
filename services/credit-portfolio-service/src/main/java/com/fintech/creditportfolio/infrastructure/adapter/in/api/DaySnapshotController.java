package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.AccountSnapshot;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.DaySnapshot;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner.Outcome;
import com.fintech.shared.time.BusinessClock;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-account day snapshot, for whoever takes it and whoever reads it.
 *
 * <ul>
 *   <li>{@code POST /api/v1/portfolio/day-snapshots/{date}}: take it if it does not exist. Charges asks
 *       before accruing; 200 with the snapshot, 202 with the reason while it cannot be taken yet.</li>
 *   <li>{@code GET .../day-snapshots/{date}}: its summary; {@code .../accounts?after&size}: the accounts
 *       by pages; {@code POST .../accounts/lookup}: a few by id (closing).</li>
 *   <li>{@code GET /api/v1/portfolio/accounts/{id}/day-snapshots?from&to}: one account's history, day by
 *       day: with it accounting and finance rebuild a statement step by step.</li>
 * </ul>
 */
@RestController
class DaySnapshotController {

    private static final int MAX_PAGE = 2000;

    private final DaySnapshotRunner runner;
    private final DaySnapshotRepository snapshots;
    private final BusinessClock clock;

    DaySnapshotController(DaySnapshotRunner runner, DaySnapshotRepository snapshots, BusinessClock clock) {
        this.runner    = runner;
        this.snapshots = snapshots;
        this.clock     = clock;
    }

    @PostMapping("/api/v1/portfolio/day-snapshots/{date}")
    ResponseEntity<Map<String, Object>> take(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(defaultValue = "api") String triggeredBy) {
        var r = runner.take(date, triggeredBy);
        HttpStatus status = r.taken() ? HttpStatus.OK
                : r.outcome() == Outcome.IN_PROGRESS ? HttpStatus.CONFLICT : HttpStatus.ACCEPTED;
        var body = new LinkedHashMap<String, Object>();
        body.put("businessDate", date.toString());
        body.put("outcome", r.outcome().name());
        body.put("detail", r.detail());
        if (r.snapshot() != null) body.put("snapshot", r.snapshot());
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/api/v1/portfolio/day-snapshots/{date}")
    ResponseEntity<DaySnapshot> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return snapshots.find(date).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/portfolio/day-snapshots/{date}/accounts")
    List<AccountSnapshot> accounts(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) UUID after,
                                   @RequestParam(defaultValue = "1000") int size) {
        return snapshots.page(date, after, Math.min(Math.max(size, 1), MAX_PAGE));
    }

    @PostMapping("/api/v1/portfolio/day-snapshots/{date}/accounts/lookup")
    List<AccountSnapshot> lookup(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestBody List<UUID> creditAccountIds) {
        return snapshots.lookup(date, creditAccountIds.stream().limit(MAX_PAGE).toList());
    }

    @GetMapping("/api/v1/portfolio/accounts/{creditAccountId}/day-snapshots")
    List<AccountSnapshot> history(@PathVariable UUID creditAccountId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate until = to != null ? to : clock.today();
        LocalDate since = from != null ? from : until.minusDays(31);
        return snapshots.history(creditAccountId, since, until);
    }
}
