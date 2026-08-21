package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AccountingClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AccountingClient.UnitMovementResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AccountingClient.VoucherResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.InvoicingClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Contabilidad para el backoffice: pólizas por sucursal y préstamo, balanza, facturas y resumen.
 *
 * <p><b>El prefijo va en plural y sin versionar</b> —{@code /accounting}, {@code /invoices}—, igual
 * que el resto de los controladores del canal. Aquí importa más que en ningún otro módulo: el
 * matcher de seguridad declarado es {@code /accounting/**}, y una ruta que no case con él cae en el
 * {@code anyRequest().authenticated()} final y responde <b>200 a cualquier empleado con sesión</b>.
 * Con el mayor contable eso significa entregarle la información financiera consolidada de la
 * institución a un gestor de cobranza.
 *
 * <p><b>El alcance se resuelve aquí, en una llamada.</b> Un {@code unitId} se traduce a los códigos
 * de su subárbol preguntando a sales-org <b>una vez</b>, y esos códigos viajan a contabilidad como
 * filtro. Contabilidad no conoce el árbol comercial, y sales-org no sabe de asientos.
 *
 * <p><b>Nada se agrega en el BFF.</b> Los totales, el desglose por sucursal y la balanza vienen
 * agregados de la base. Si el BFF sumara, el total del período y el de su propia balanza podrían
 * discrepar y no habría forma de saber cuál es el bueno.
 */
@RestController
@Tag(name = "Contabilidad", description = "Pólizas, balanza, facturas y cierre de período")
class AccountingController {

    private static final Logger log = LoggerFactory.getLogger(AccountingController.class);

    /** Códigos por lote al hidratar una página. Una página son 25 filas, no la cartera entera. */
    private static final int MAX_BATCH = 200;

    private final AccountingClient accountingClient;
    private final InvoicingClient invoicingClient;
    private final SalesOrgClient salesOrgClient;
    private final PartyClient partyClient;
    private final CreditPortfolioClient creditPortfolioClient;

    AccountingController(AccountingClient accountingClient, InvoicingClient invoicingClient,
                         SalesOrgClient salesOrgClient, PartyClient partyClient,
                         CreditPortfolioClient creditPortfolioClient) {
        this.accountingClient      = accountingClient;
        this.invoicingClient       = invoicingClient;
        this.salesOrgClient        = salesOrgClient;
        this.partyClient           = partyClient;
        this.creditPortfolioClient = creditPortfolioClient;
    }

    // ── Períodos ─────────────────────────────────────────────────────────────

    @GetMapping("/accounting/periods")
    @Operation(summary = "Períodos contables con su estatus")
    ResponseEntity<List<Map<String, Object>>> periods() {
        List<Map<String, Object>> body = safe(accountingClient.periods()).stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("period", p.period());
                    m.put("status", p.status());
                    m.put("closedAt", p.closedAt());
                    m.put("vouchers", p.vouchers());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/accounting/periods/{period}/close")
    @Operation(summary = "Cierra un período contable")
    ResponseEntity<Map<String, Object>> close(@PathVariable String period) {
        log.info("POST /accounting/periods/{}/close", period);
        return ResponseEntity.ok(accountingClient.closePeriod(period));
    }

    @PostMapping("/accounting/periods/{period}/reopen")
    @Operation(summary = "Reabre un período contable")
    ResponseEntity<Map<String, Object>> reopen(@PathVariable String period) {
        log.info("POST /accounting/periods/{}/reopen", period);
        return ResponseEntity.ok(accountingClient.reopenPeriod(period));
    }

    // ── Resumen ──────────────────────────────────────────────────────────────

    @GetMapping("/accounting/summary")
    @Operation(summary = "Panorama del período con desglose por sucursal")
    ResponseEntity<Map<String, Object>> summary(@RequestParam String period,
                                                 @RequestParam(required = false) UUID unitId,
                                                 @RequestParam(required = false) String unitCode) {
        List<String> scope = scopeCodes(unitId, unitCode);
        var s = accountingClient.summary(period, scope, scope.isEmpty());

        Map<String, Object> body = new LinkedHashMap<>();
        if (s == null) return ResponseEntity.ok(body);

        body.put("period", s.period());
        body.put("periodStatus", s.periodStatus());
        body.put("vouchers", s.vouchers());
        body.put("totalDebit", s.totalDebit());
        body.put("totalCredit", s.totalCredit());
        body.put("balanced", s.balanced());
        body.put("orderAccounts", s.orderAccounts());
        body.put("accruedInterest", s.accruedInterest());
        body.put("moratoriumInterest", s.moratoriumInterest());
        body.put("fees", s.fees());
        body.put("provision", s.provision());
        body.put("writeOff", s.writeOff());
        body.put("recovery", s.recovery());
        body.put("loansRegistered", s.loansRegistered());
        body.put("byUnit", withUnitNames(safe(s.byUnit())));

        // Lo facturado del período sale de invoicing y no de contabilidad: son dos hechos distintos
        // —lo devengado y lo timbrado— y mezclarlos en un servicio haría imposible ver la diferencia,
        // que es justo el número que interesa (devengado sin facturar).
        var invoices = invoicingClient.search(period, null, null, null, 0, MAX_BATCH);
        var vivas = invoices == null ? List.<InvoicingClient.InvoiceResponse>of()
                : safe(invoices.content()).stream()
                        .filter(i -> !"CANCELLED".equalsIgnoreCase(i.status())).toList();
        java.math.BigDecimal invoiced = vivas.stream()
                .map(InvoicingClient.InvoiceResponse::total)
                .filter(Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        // ── Conciliación del período ─────────────────────────────────────────
        //
        // Lo facturable **incluye el IVA**, porque el total de un CFDI lo incluye. Restar un
        // devengado sin IVA contra un facturado con IVA daba una diferencia igual al impuesto —aquí,
        // 50,766.20 sobre 1.38 M— que además se recortaba a cero por el `max(ZERO)` y escondía las
        // dos cosas a la vez: el error de la resta y el hecho de que el período cuadra exacto.
        java.math.BigDecimal facturable = nz(s.accruedInterest())
                .add(nz(s.moratoriumInterest()))
                .add(nz(s.fees()))
                .add(nz(s.ivaAccrued()));
        java.math.BigDecimal diferencia = facturable.subtract(invoiced);

        body.put("ivaAccrued", s.ivaAccrued());
        body.put("billable", facturable);
        body.put("invoiced", invoiced);
        body.put("invoiceCount", vivas.size());
        // Con signo: negativo significa facturado de más —un CFDI de otro período, o una corrida
        // repetida— y recortarlo a cero haría desaparecer justo el caso que hay que investigar.
        body.put("billingDifference", diferencia);
        body.put("billingReconciled", diferencia.abs().compareTo(new java.math.BigDecimal("0.01")) < 0);
        body.put("pendingToBill", diferencia.max(java.math.BigDecimal.ZERO));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/accounting/units")
    @Operation(summary = "Movimiento contable por sucursal del período")
    ResponseEntity<List<Map<String, Object>>> units(@RequestParam String period,
                                                     @RequestParam(required = false) UUID unitId,
                                                     @RequestParam(required = false) String unitCode) {
        return ResponseEntity.ok(withUnitNames(safe(
                accountingClient.units(period, scopeCodes(unitId, unitCode)))));
    }

    // ── Pólizas ──────────────────────────────────────────────────────────────

    @GetMapping("/accounting/vouchers")
    @Operation(summary = "Libro de pólizas paginado, filtrable por sucursal, tipo y préstamo")
    ResponseEntity<Map<String, Object>> vouchers(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) String unitCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID creditAccountId,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var result = accountingClient.vouchers(period, scopeCodes(unitId, unitCode), type,
                creditAccountId, partyId, page, size);
        List<VoucherResponse> rows = result == null ? List.of() : safe(result.content());

        Map<String, Atribucion> attr   = atribuciones();
        Map<UUID, String> partyNames   = resolveParties(rows);
        Map<UUID, String> contractNums = resolveContracts(rows);

        List<Map<String, Object>> content = rows.stream()
                .map(v -> voucherView(v, attr, partyNames, contractNums, false))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result == null ? page : result.page());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    /**
     * El período agrupado por préstamo: una fila por crédito, con sus totales del mes.
     *
     * <p>Es la misma información del libro de pólizas contada por el eje con el que se concilia. Con
     * devengo diario un mes son miles de pólizas, así que agruparlas en el navegador significaría
     * traérselas todas para pintar cien filas — y truncar en cuanto la cartera crezca, presentando
     * una suma parcial como si fuera el total.
     *
     * <p>El nivel de <b>cliente</b> se arma sobre estas filas, ya sumadas: los préstamos de un
     * período son cientos, no miles, y agruparlos por su party es exacto y barato.
     *
     * <p>Las pólizas de cada fila NO vienen aquí: se piden con
     * {@code /accounting/vouchers?creditAccountId=} al expandir.
     */
    @GetMapping("/accounting/loans")
    @Operation(summary = "Movimiento contable del período agrupado por préstamo")
    ResponseEntity<Map<String, Object>> loans(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) String unitCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var result = accountingClient.loans(period, scopeCodes(unitId, unitCode), page, size);
        List<AccountingClient.LoanMovementResponse> rows = result == null ? List.of() : safe(result.content());

        Map<String, Atribucion> attr = atribuciones();
        Map<UUID, String> partyNames = new HashMap<>();
        partiesOf(rows.stream().map(AccountingClient.LoanMovementResponse::obligorPartyId)
                .filter(Objects::nonNull).limit(MAX_BATCH).collect(Collectors.toSet()))
                .forEach((id, p) -> partyNames.put(id, BackofficeViews.fullName(p)));
        Map<UUID, String> contracts = contractsOf(
                rows.stream().map(AccountingClient.LoanMovementResponse::creditAccountId)
                        .filter(Objects::nonNull).limit(MAX_BATCH).collect(Collectors.toSet()));

        List<Map<String, Object>> content = rows.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("creditAccountId", l.creditAccountId());
            m.put("contractNumber", contracts.get(l.creditAccountId()));
            m.put("obligorPartyId", l.obligorPartyId());
            m.put("obligorName", l.obligorPartyId() == null ? null : partyNames.get(l.obligorPartyId()));
            m.put("orgUnitCode", l.orgUnitCode());
            ponAtribucion(m, l.orgUnitCode(), attr);
            m.put("vouchers", l.vouchers());
            m.put("firstVoucherDate", l.firstVoucherDate());
            m.put("lastVoucherDate", l.lastVoucherDate());
            m.put("totalDebit", l.totalDebit());
            m.put("orderAccounts", l.orderAccounts());
            m.put("accruedInterest", l.accruedInterest());
            m.put("moratoriumInterest", l.moratoriumInterest());
            m.put("fees", l.fees());
            m.put("provision", l.provision());
            m.put("writeOff", l.writeOff());
            m.put("disbursed", l.disbursed());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result == null ? page : result.page());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/accounting/vouchers/{voucherId}")
    @Operation(summary = "Una póliza con sus renglones")
    ResponseEntity<Map<String, Object>> voucher(@PathVariable UUID voucherId) {
        var v = accountingClient.voucher(voucherId);
        if (v == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(voucherView(v, atribuciones(),
                resolveParties(List.of(v)), resolveContracts(List.of(v)), true));
    }

    // ── Balanza ──────────────────────────────────────────────────────────────

    @GetMapping("/accounting/trial-balance")
    @Operation(summary = "Balanza de comprobación del período, acotable por sucursal")
    ResponseEntity<List<AccountingClient.TrialBalanceRowResponse>> trialBalance(
            @RequestParam String period,
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) String unitCode) {
        return ResponseEntity.ok(safe(accountingClient.trialBalance(period, scopeCodes(unitId, unitCode))));
    }

    // ── Ficha contable de un préstamo ────────────────────────────────────────

    /**
     * Todo lo contable de un crédito, compuesto.
     *
     * <p>Es una entidad única, así que el fan-out está permitido y es el patrón correcto: contabilidad
     * para las pólizas y los saldos, cartera para el contrato y los montos, party para el nombre e
     * invoicing para sus facturas. Cada pieza secundaria se degrada sola — sin cartera se pierde el
     * número de contrato, no la ficha.
     */
    @GetMapping("/accounting/accounts/{creditAccountId}/ledger")
    @Operation(summary = "Ficha contable de un préstamo: saldos, pólizas y facturas")
    ResponseEntity<Map<String, Object>> loanLedger(@PathVariable UUID creditAccountId) {
        var ledger = accountingClient.loanLedger(creditAccountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("creditAccountId", creditAccountId);

        final CreditPortfolioClient.CreditAccountResponse account = fetchAccount(creditAccountId);

        UUID partyId = ledger != null && ledger.obligorPartyId() != null
                ? ledger.obligorPartyId()
                : account != null ? account.obligorPartyId() : null;

        body.put("contractNumber", account != null ? account.contractNumber() : null);
        body.put("productType", account != null ? account.productType() : null);
        body.put("authorizedAmount", account != null ? account.creditLimit() : null);
        body.put("disbursedAmount", account != null ? account.principalBalance() : null);
        body.put("originatedAt", account != null ? account.activatedAt() : null);
        body.put("obligorPartyId", partyId);
        body.put("obligorName", partyId == null ? null : nameOf(partyId));

        // Una sola lectura del árbol para la ficha entera: la cabecera y cada una de sus pólizas.
        Map<String, Atribucion> attrLedger = atribuciones();

        String unitCode = ledger != null ? ledger.orgUnitCode() : null;
        body.put("orgUnitCode", unitCode);
        ponAtribucion(body, unitCode, attrLedger);

        body.put("balances", ledger == null ? List.of() : safe(ledger.balances()));
        body.put("vouchers", ledger == null ? List.of() : safe(ledger.vouchers()).stream()
                .map(v -> voucherView(v, attrLedger,
                        partyId == null ? Map.of() : Map.of(partyId, String.valueOf(body.get("obligorName"))),
                        account == null ? Map.of() : Map.of(creditAccountId, account.contractNumber()),
                        false))
                .toList());

        try {
            var invoices = invoicingClient.search(null, null, null, creditAccountId, 0, MAX_BATCH);
            body.put("invoices", invoices == null ? List.of()
                    : safe(invoices.content()).stream().map(this::invoiceView).toList());
        } catch (Exception ex) {
            log.warn("Sin facturas para el crédito {}: {}", creditAccountId, ex.getMessage());
            body.put("invoices", List.of());
        }

        return ResponseEntity.ok(body);
    }

    /** Cartera es secundaria en la ficha: sin ella se pierde el contrato, no la contabilidad. */
    private CreditPortfolioClient.CreditAccountResponse fetchAccount(UUID creditAccountId) {
        try {
            return creditPortfolioClient.getById(creditAccountId);
        } catch (Exception ex) {
            log.warn("Sin datos de cartera para el crédito {}: {}", creditAccountId, ex.getMessage());
            return null;
        }
    }

    // ── Facturas ─────────────────────────────────────────────────────────────

    @GetMapping("/invoices")
    @Operation(summary = "Facturas filtrables por período, estatus, cliente y crédito")
    ResponseEntity<Map<String, Object>> invoices(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) UUID creditAccountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var result = invoicingClient.search(period, status, partyId, creditAccountId, page, size);
        var rows = result == null ? List.<InvoicingClient.InvoiceResponse>of() : safe(result.content());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", rows.stream().map(this::invoiceView).toList());
        body.put("page", result == null ? page : result.page());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/invoices/{invoiceId}")
    @Operation(summary = "Detalle de una factura con sus líneas por préstamo")
    ResponseEntity<Map<String, Object>> invoice(@PathVariable UUID invoiceId) {
        var inv = invoicingClient.byId(invoiceId);
        if (inv == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new LinkedHashMap<>(invoiceView(inv));
        body.put("receptorRegime", inv.receptorRegime());
        body.put("cfdiUse", inv.cfdiUse());

        // El número de contrato de cada línea: la línea trae el id del crédito y quien concilia
        // trabaja con el contrato. Se resuelve en lote — una factura de tres préstamos es una llamada.
        Map<UUID, String> contracts = contractsOf(safe(inv.lines()).stream()
                .map(InvoicingClient.InvoiceLineResponse::creditAccountId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        body.put("lines", safe(inv.lines()).stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("concept", l.concept());
            m.put("creditAccountId", l.creditAccountId());
            m.put("contractNumber", l.creditAccountId() == null ? null : contracts.get(l.creditAccountId()));
            m.put("amount", l.amount());
            m.put("isIva", l.isIva());
            return m;
        }).toList());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/accounting/billing-runs")
    @Operation(summary = "Dispara la corrida de facturación del período")
    ResponseEntity<Map<String, Object>> runBilling(@RequestParam String period) {
        log.info("POST /accounting/billing-runs period={}", period);
        return ResponseEntity.ok(accountingClient.runBilling(period));
    }

    // ── Reconciliación ───────────────────────────────────────────────────────

    /**
     * Sella la sucursal de origen de la cartera que nació antes de que existiera el campo.
     *
     * <p>Es una <b>reconciliación</b>, no una operación: se corre una vez tras desplegar y queda
     * disponible por si entra cartera migrada. Vive en el BFF y no en un script porque toda la
     * orquestación que necesita —cartera, party y el árbol comercial— ya está aquí, y meterle a
     * cartera un cliente HTTP hacia otro dominio para un caso que ocurre una vez rompería la
     * topología por comodidad.
     *
     * <p><b>Resuelve, no adivina.</b> Cliente → ejecutivo asignado → nodo del ejecutivo en el árbol →
     * su sucursal. Lo que no se resuelva se queda sin sucursal y se reporta contado: repartirlo con
     * una heurística haría que las sumas cerraran a costa de atribuirle ingreso a quien no lo colocó,
     * y eso no se puede auditar después.
     *
     * <p>El árbol y los niveles se leen <b>una vez</b> —{@code party_ref} ya enlaza cada nodo de
     * ejecutivo con su empleado—, así que no hay una llamada por crédito.
     */
    @PostMapping("/accounting/backfill-origin-units")
    @Operation(summary = "Sella la sucursal de origen de la cartera anterior al sellado (idempotente)")
    ResponseEntity<Map<String, Object>> backfillOriginUnits(
            @RequestParam(defaultValue = "20000") int max,
            @RequestParam(defaultValue = "false") boolean dryRun) {

        var arbol = new ArbolComercial();

        int revisados = 0, sellados = 0, sinEjecutivo = 0, sinUnidad = 0, yaSellados = 0;
        int polizasAtribuidas = 0;
        List<String> muestra = new ArrayList<>();

        // **Todas las páginas, no la primera.** Con tope de 200 y 469 créditos, dos tercios de la
        // cartera nunca se tocaban: quedaban sin sucursal y la estructura comercial los enseñaba como
        // «sin cartera». El fallo no se veía en pruebas porque con 24 créditos cabía todo en una
        // página — sólo aparece al crecer, que es cuando ya nadie sospecha de la paginación.
        List<CreditPortfolioClient.CreditAccountResponse> accounts = new ArrayList<>();
        for (int pagina = 0; accounts.size() < max; pagina++) {
            var page = creditPortfolioClient.search(null, null, null, null, null, null,
                    pagina, MAX_BATCH, "createdAt,asc");
            List<CreditPortfolioClient.CreditAccountResponse> lote =
                    page == null ? List.of() : safe(page.content());
            if (lote.isEmpty()) break;
            accounts.addAll(lote);
            if (page != null && pagina + 1 >= page.totalPages()) break;
        }
        log.info("Reconciliación de sucursal sobre {} cuentas", accounts.size());

        Map<UUID, PartyClient.PartyResponse> parties = partiesOf(accounts.stream()
                .map(CreditPortfolioClient.CreditAccountResponse::obligorPartyId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        for (var a : accounts) {
            revisados++;

            // Un crédito ya sellado no se vuelve a sellar, pero SÍ se le atribuyen las pólizas: el
            // sello y la atribución son dos pasos y pueden haber quedado desfasados —si el primero
            // corrió y el segundo falló, saltarse el crédito dejaría la contabilidad de esa sucursal
            // incompleta para siempre. La atribución es idempotente: sólo rellena lo que está en nulo.
            if (a.originUnitCode() != null && !a.originUnitCode().isBlank()) {
                yaSellados++;
                if (!dryRun) polizasAtribuidas += atribuir(a.creditAccountId(), a.originUnitCode());
                continue;
            }

            var party = parties.get(a.obligorPartyId());
            if (party == null || party.assignedExecutiveId() == null) { sinEjecutivo++; continue; }

            String branch = arbol.unidadDe(party.assignedExecutiveId());
            if (branch == null) { sinUnidad++; continue; }

            if (!dryRun) {
                try {
                    creditPortfolioClient.sealOriginUnit(a.creditAccountId(), branch);
                } catch (Exception ex) {
                    log.warn("No se pudo sellar el crédito {}: {}", a.creditAccountId(), ex.getMessage());
                    continue;
                }
                // Y se le pone la sucursal a lo que ya estaba asentado. Va después del sello y no
                // antes: si cartera rechaza, contabilidad no debe quedar atribuyendo a una sucursal
                // que el préstamo no tiene.
                polizasAtribuidas += atribuir(a.creditAccountId(), branch);
            }
            sellados++;
            if (muestra.size() < 10) muestra.add(a.contractNumber() + " → " + branch);
        }

        // Barrido final: la atribución de arriba va crédito por crédito sobre lo que devuelve la
        // página de cartera, y lo que no caiga ahí se queda sin sucursal aunque su crédito ya la
        // tenga sellada. El barrido no pagina —sale del shadow de contabilidad— así que cierra ese
        // hueco por construcción en vez de depender del tamaño de la página.
        int barridas = 0;
        if (!dryRun) {
            try {
                var r = accountingClient.sweepUnits();
                barridas = r == null ? 0 : ((Number) r.getOrDefault("vouchersUpdated", 0)).intValue();
            } catch (Exception ex) {
                log.warn("El barrido de sucursales falló: {}", ex.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dryRun", dryRun);
        body.put("revisados", revisados);
        body.put("sellados", sellados);
        body.put("polizasAtribuidas", polizasAtribuidas);
        body.put("polizasBarridas", barridas);
        body.put("yaSellados", yaSellados);
        body.put("sinEjecutivoAsignado", sinEjecutivo);
        body.put("ejecutivoFueraDelArbol", sinUnidad);
        body.put("muestra", muestra);
        log.info("Backfill de sucursal de origen: {}", body);
        return ResponseEntity.ok(body);
    }

    /** Cuántas pólizas quedaron atribuidas. Que falle no invalida el sello, que ya está puesto. */
    private int atribuir(UUID creditAccountId, String orgUnitCode) {
        try {
            var r = accountingClient.attributeUnit(creditAccountId, orgUnitCode);
            return r == null ? 0 : ((Number) r.getOrDefault("vouchersUpdated", 0)).intValue();
        } catch (Exception ex) {
            log.warn("Crédito {} sellado, pero sus pólizas no se atribuyeron: {}",
                    creditAccountId, ex.getMessage());
            return 0;
        }
    }

    /**
     * El árbol comercial, leído una vez, para resolver «de qué sucursal es este ejecutivo».
     *
     * <p>Hay <b>dos formas</b> de que un empleado cuelgue del árbol y las dos existen en la
     * práctica: como <i>nodo</i> —{@code party_ref} lo enlaza con su unidad— o como <i>asignación</i>
     * en {@code unit_assignments}. Resolver sólo la primera devuelve vacío en una instalación que use
     * la segunda, y el síntoma no parece un error: parece que nadie tiene ejecutivo asignado.
     *
     * <p>El nodo se resuelve gratis (sale de la misma lectura del árbol); la asignación cuesta una
     * llamada por ejecutivo distinto y se cachea. En una reconciliación que corre una vez sobre
     * decenas de ejecutivos eso es aceptable; en una consulta de pantalla no lo sería.
     */
    private final class ArbolComercial {

        private final Map<String, String> nivelPorId = new HashMap<>();
        private final Map<String, Map<String, Object>> unidadPorId = new HashMap<>();
        private final Map<UUID, String> porPartyRef = new HashMap<>();
        private final Map<UUID, String> cache = new HashMap<>();

        ArbolComercial() {
            try {
                for (var l : safe(salesOrgClient.listLevels())) {
                    nivelPorId.put(String.valueOf(l.get("levelId")), String.valueOf(l.get("code")));
                }
                for (var u : safe(salesOrgClient.listUnits())) {
                    unidadPorId.put(String.valueOf(u.get("unitId")), u);
                    Object ref = u.get("partyRef");
                    if (ref == null) continue;
                    try {
                        porPartyRef.put(UUID.fromString(String.valueOf(ref)), String.valueOf(u.get("code")));
                    } catch (IllegalArgumentException ignored) {
                        // Un party_ref que no es un UUID es un dato malo, no un motivo para no resolver
                        // el resto del árbol.
                    }
                }
            } catch (Exception ex) {
                log.warn("No se pudo leer el árbol comercial: {}", ex.getMessage());
            }
        }

        /** El código de la unidad a la que se atribuye lo que coloca este empleado. */
        String unidadDe(UUID staffUserId) {
            if (staffUserId == null) return null;
            if (cache.containsKey(staffUserId)) return cache.get(staffUserId);

            String code = porPartyRef.get(staffUserId);
            if (code == null) code = porAsignacion(staffUserId);
            cache.put(staffUserId, code);
            return code;
        }

        private String porAsignacion(UUID staffUserId) {
            try {
                var asignacion = salesOrgClient.currentAssignment("STAFF", staffUserId);
                if (asignacion == null || asignacion.get("unitId") == null) return null;
                return subirHastaSucursal(unidadPorId.get(String.valueOf(asignacion.get("unitId"))));
            } catch (Exception ex) {
                return null;
            }
        }

        /**
         * Sube hasta la sucursal, y <b>sólo si el nodo está por debajo</b>.
         *
         * <p>Un ejecutivo cuelga de una sucursal y hay que subir; un gerente de zona cuelga de la
         * zona y no hay ninguna sucursal encima. Subir siempre acabaría atribuyéndole a la raíz
         * nacional todo lo que coloca la dirección, que es peor que dejarlo en su propia unidad.
         */
        private String subirHastaSucursal(Map<String, Object> nodo) {
            int guarda = 0;   // tope contra un árbol con ciclo: un dato malo no cuelga la operación
            while (nodo != null && guarda++ < 10) {
                String nivel = nivelPorId.get(String.valueOf(nodo.get("levelId")));
                if (nivel == null || !"EXECUTIVE".equals(nivel) && !"DISTRIBUTOR".equals(nivel)) break;
                Object padre = nodo.get("parentUnitId");
                if (padre == null) break;
                Map<String, Object> siguiente = unidadPorId.get(String.valueOf(padre));
                if (siguiente == null) break;
                nodo = siguiente;
            }
            return nodo == null || nodo.get("code") == null ? null : String.valueOf(nodo.get("code"));
        }
    }

    private Map<UUID, PartyClient.PartyResponse> partiesOf(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<UUID, PartyClient.PartyResponse> out = new HashMap<>();
        try {
            for (var p : safe(partyClient.batch(ids, "prospectId"))) {
                if (p.prospectId() != null) out.put(p.prospectId(), p);
            }
            Set<UUID> pendientes = ids.stream()
                    .filter(id -> !out.containsKey(id)).collect(Collectors.toSet());
            if (!pendientes.isEmpty()) {
                for (var p : safe(partyClient.batch(pendientes, "partyId"))) {
                    if (p.partyId() != null) out.put(p.partyId(), p);
                }
            }
        } catch (Exception ex) {
            log.warn("No se pudieron resolver clientes en lote: {}", ex.getMessage());
        }
        return out;
    }

    // ── Alcance ──────────────────────────────────────────────────────────────

    /**
     * Los códigos de unidad que abarca la consulta.
     *
     * <p>Una sola llamada a sales-org por petición: pedir «la región Norte» trae su subárbol completo
     * y de ahí salen los códigos. La alternativa —resolver la unidad de cada póliza— es una llamada
     * por fila para pintar una tabla, que es el fan-out que este repo tiene prohibido.
     *
     * <p>{@code SIN_SUCURSAL} es un código reservado: no es una unidad, es la ausencia de una, y sirve
     * para ver los créditos anteriores al sellado sin que se pierdan en el total.
     */
    private List<String> scopeCodes(UUID unitId, String unitCode) {
        // Un código concreto no necesita resolverse contra el árbol: es una hoja, y `SIN_SUCURSAL`
        // —los créditos anteriores al sellado— ni siquiera es una unidad, así que preguntarle a
        // sales-org por su subárbol devolvería vacío y la pantalla se quedaría en blanco justo en el
        // hueco que hay que ver.
        if (unitCode != null && !unitCode.isBlank()) return List.of(unitCode);
        return scopeCodes(unitId);
    }

    private List<String> scopeCodes(UUID unitId) {
        if (unitId == null) return List.of();
        try {
            List<String> codes = safe(salesOrgClient.subtree(unitId)).stream()
                    .map(u -> String.valueOf(u.get("code")))
                    .filter(c -> c != null && !"null".equals(c))
                    .toList();
            // Un subárbol vacío no puede degradarse a «sin filtro»: devolvería la contabilidad de
            // toda la institución a quien pidió una sucursal. Se manda un código imposible.
            return codes.isEmpty() ? List.of("__SIN_ALCANCE__") : codes;
        } catch (Exception ex) {
            log.warn("No se pudo resolver el alcance de la unidad {}: {}", unitId, ex.getMessage());
            return List.of("__SIN_ALCANCE__");
        }
    }

    /** código → nombre, en una llamada. El árbol completo son decenas de filas, no miles. */
    private Map<String, String> unitNames() {
        try {
            Map<String, String> names = new HashMap<>();
            for (var u : safe(salesOrgClient.listUnits())) {
                Object code = u.get("code");
                Object name = u.get("name");
                if (code != null) names.put(String.valueOf(code), String.valueOf(name));
            }
            return names;
        } catch (Exception ex) {
            log.warn("No se pudieron resolver los nombres de las unidades: {}", ex.getMessage());
            return Map.of();
        }
    }

    /** El nivel Sucursal del árbol comercial. La atribución contable se presenta siempre a esta altura. */
    private static final String NIVEL_SUCURSAL = "11111111-0000-0000-0000-000000000003";

    /**
     * La sucursal de una unidad sellada, y quién responde por ella.
     *
     * @param sucursal    nombre de la sucursal; nunca el de una persona
     * @param responsable el ejecutivo que colocó, o {@code null} si la unidad sellada ya era la sucursal
     */
    private record Atribucion(String codigo, String sucursal, String responsable) {}

    /**
     * Resuelve cada unidad sellada a <b>sucursal + responsable</b>.
     *
     * <p>Lo que un crédito sella es la unidad del <b>ejecutivo</b> —61 de 64 en la cartera actual—, y
     * el nombre de esa unidad es el nombre de la persona. Presentado tal cual, la columna «Sucursal»
     * mostraba «Néstor Fuentes Escobar»: no es un dato equivocado, es el dato de otro nivel del
     * árbol, y leído como sucursal impide agrupar por plaza y hace parecer que hay tantas sucursales
     * como ejecutivos.
     *
     * <p>Se sube por {@code parentUnitId} hasta el nivel Sucursal en vez de partir el {@code path}
     * por convención de prefijos: el path es texto y una unidad renombrada rompería el troceo sin
     * avisar, mientras que el nivel es un dato explícito del árbol.
     */
    private Map<String, Atribucion> atribuciones() {
        try {
            Map<String, Map<String, Object>> porId = new HashMap<>();
            List<Map<String, Object>> unidades = safe(salesOrgClient.listUnits());
            for (var u : unidades) {
                Object id = u.get("unitId");
                if (id != null) porId.put(String.valueOf(id), u);
            }

            Map<String, Atribucion> out = new HashMap<>();
            for (var u : unidades) {
                Object code = u.get("code");
                if (code == null) continue;
                String codigo = String.valueOf(code);

                // Se sube hasta la sucursal, recordando de dónde se partió: si la unidad sellada
                // está por debajo, esa unidad es el responsable.
                Map<String, Object> actual = u;
                String responsable = null;
                int guarda = 0;   // el árbol tiene seis niveles; el tope sólo evita un ciclo por dato corrupto
                while (actual != null && !NIVEL_SUCURSAL.equals(String.valueOf(actual.get("levelId")))
                        && guarda++ < 10) {
                    if (responsable == null) responsable = String.valueOf(actual.get("name"));
                    Object padre = actual.get("parentUnitId");
                    actual = padre == null ? null : porId.get(String.valueOf(padre));
                }

                if (actual != null) {
                    out.put(codigo, new Atribucion(codigo, String.valueOf(actual.get("name")), responsable));
                } else {
                    // Por encima de sucursal (zona, región, nacional): no hay plaza que mostrar y el
                    // nombre de la unidad ya es el correcto para su nivel.
                    out.put(codigo, new Atribucion(codigo, String.valueOf(u.get("name")), null));
                }
            }
            return out;
        } catch (Exception ex) {
            log.warn("No se pudo resolver la atribución por sucursal: {}", ex.getMessage());
            return Map.of();
        }
    }

    /** Pone `orgUnitName` (la sucursal) y `orgUnitManager` (quién responde) en un cuerpo de salida. */
    private void ponAtribucion(Map<String, Object> m, String codigo, Map<String, Atribucion> attr) {
        if (codigo == null) {
            // Sin código no es un fallo de resolución: es un crédito anterior al sellado, y la
            // pantalla tiene que poder contarlo aparte en vez de esconderlo.
            m.put("orgUnitName", "Sin sucursal");
            m.put("orgUnitManager", null);
            return;
        }
        Atribucion a = attr.get(codigo);
        m.put("orgUnitName", a != null ? a.sucursal() : codigo);
        m.put("orgUnitManager", a != null ? a.responsable() : null);
    }

    /** código → unitId, de la misma lectura del árbol. */
    private Map<String, String> unitIds() {
        try {
            Map<String, String> ids = new HashMap<>();
            for (var u : safe(salesOrgClient.listUnits())) {
                Object code = u.get("code");
                Object id   = u.get("unitId");
                if (code != null && id != null) ids.put(String.valueOf(code), String.valueOf(id));
            }
            return ids;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> withUnitNames(List<UnitMovementResponse> rows) {
        Map<String, Atribucion> attr = atribuciones();
        Map<String, String> ids   = unitIds();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var u : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orgUnitCode", u.orgUnitCode());
            ponAtribucion(m, u.orgUnitCode(), attr);
            // El id permite pedir el subárbol de una rama; el código, sólo esa unidad. La pantalla
            // necesita los dos para poder ofrecer «esta sucursal» y «esta región y lo que cuelga».
            m.put("orgUnitId", u.orgUnitCode() == null ? null : ids.get(u.orgUnitCode()));
            m.put("vouchers", u.vouchers());
            m.put("loans", u.loans());
            m.put("totalDebit", u.totalDebit());
            m.put("totalCredit", u.totalCredit());
            m.put("accruedInterest", u.accruedInterest());
            m.put("moratoriumInterest", u.moratoriumInterest());
            m.put("fees", u.fees());
            m.put("provision", u.provision());
            out.add(m);
        }
        return out;
    }

    // ── Vistas ───────────────────────────────────────────────────────────────

    private Map<String, Object> voucherView(VoucherResponse v, Map<String, Atribucion> attr,
                                             Map<UUID, String> partyNames, Map<UUID, String> contracts,
                                             boolean withLines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", v.voucherId());
        m.put("voucherType", v.voucherType());
        m.put("folio", v.folio());
        m.put("period", v.period());
        m.put("voucherDate", v.voucherDate());
        m.put("orgUnitCode", v.orgUnitCode());
        ponAtribucion(m, v.orgUnitCode(), attr);
        m.put("concept", v.concept());
        m.put("triggerEvent", v.triggerEvent());
        m.put("sourceEventId", v.sourceEventId());
        m.put("creditAccountId", v.creditAccountId());
        m.put("contractNumber", v.creditAccountId() == null ? null : contracts.get(v.creditAccountId()));
        m.put("obligorPartyId", v.obligorPartyId());
        m.put("obligorName", v.obligorPartyId() == null ? null : partyNames.get(v.obligorPartyId()));
        m.put("totalDebit", v.totalDebit());
        m.put("totalCredit", v.totalCredit());
        m.put("status", v.status());
        m.put("isLatePosting", v.latePosting());
        m.put("originalPeriod", v.originalPeriod());
        m.put("reversalRef", v.reversalRef());
        if (withLines) m.put("lines", safe(v.lines()));
        return m;
    }

    private Map<String, Object> invoiceView(InvoicingClient.InvoiceResponse i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceId", i.invoiceId());
        m.put("period", i.period());
        m.put("obligorPartyId", i.obligorPartyId());
        m.put("receptorName", i.receptorName());
        m.put("receptorRfc", i.receptorRfc());
        m.put("subtotal", i.subtotal());
        m.put("iva", i.iva());
        m.put("total", i.total());
        m.put("currency", i.currency());
        m.put("status", i.status());
        m.put("folioFiscal", i.folioFiscal());
        m.put("serie", i.serie());
        m.put("folio", i.folio());
        m.put("stampedAt", i.stampedAt());
        m.put("createdAt", i.stampedAt());
        // Cuántos préstamos componen el CFDI. Una factura de dos créditos no se puede cuadrar contra
        // un solo contrato, y quien la abra desde un contrato tiene que saberlo antes de cuadrar.
        m.put("loanCount", safe(i.lines()).stream()
                .map(InvoicingClient.InvoiceLineResponse::creditAccountId)
                .filter(Objects::nonNull).distinct().count());
        return m;
    }

    // ── Resolución en lote ───────────────────────────────────────────────────

    private Map<UUID, String> resolveParties(List<VoucherResponse> rows) {
        Set<UUID> ids = rows.stream().map(VoucherResponse::obligorPartyId)
                .filter(Objects::nonNull).limit(MAX_BATCH).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        partiesOf(ids).forEach((id, p) -> names.put(id, BackofficeViews.fullName(p)));
        return names;
    }

    private Map<UUID, String> resolveContracts(List<VoucherResponse> rows) {
        return contractsOf(rows.stream().map(VoucherResponse::creditAccountId)
                .filter(Objects::nonNull).limit(MAX_BATCH).collect(Collectors.toSet()));
    }

    private Map<UUID, String> contractsOf(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        try {
            Map<UUID, String> out = new HashMap<>();
            for (var a : safe(creditPortfolioClient.batch(ids))) {
                if (a.creditAccountId() != null) out.put(a.creditAccountId(), a.contractNumber());
            }
            return out;
        } catch (Exception ex) {
            log.warn("No se pudieron resolver contratos en lote: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String nameOf(UUID partyId) {
        var p = partiesOf(Set.of(partyId)).get(partyId);
        return p == null ? null : BackofficeViews.fullName(p);
    }

    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }
}
