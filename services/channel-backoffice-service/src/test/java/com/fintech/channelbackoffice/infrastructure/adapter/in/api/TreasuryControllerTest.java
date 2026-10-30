package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BankingClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.DisbursementClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Bancos / Tesorería en el BFF: que traduzca al contrato de la consola, que componga la ficha con una
 * llamada por fuente y que una fuente caída degrade sólo su bloque.
 *
 * <p>La autorización no se prueba aquí: la decide {@code SecurityConfig} por capacidad
 * ({@code treasury.view}) antes de llegar al controlador, y el reparto de esa capacidad lo fija
 * {@code PermissionsServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class TreasuryControllerTest {

    @Mock BankingClient banking;
    @Mock DisbursementClient disbursements;
    @Mock StpClient stp;

    TreasuryController controller;

    private static final UUID ACCOUNT = UUID.fromString("9b1d0000-0000-4000-8000-000000000001");
    private static final UUID CREDIT  = UUID.fromString("71d51229-0676-4f9c-941e-4dc65f5c53c3");
    private static final UUID DISPO   = UUID.fromString("b71cbdae-3e97-42ae-bf53-b5f626deefe4");
    private static final String CLAVE = "KRD2026103000000000000004";

    @BeforeEach
    void setUp() { controller = new TreasuryController(banking, disbursements, stp); }

    private static BankingClient.BankAccount cuenta(UUID id) {
        return new BankingClient.BankAccount(id, null, "646", "STP", "****0012",
                "FINTECH SERVICES SA DE CV SOFOM ENR", "MXN", "1101", "ACTIVE");
    }

    private static BankingClient.PayoutRoute ruta(UUID account, String provider, int priority, boolean enabled) {
        return new BankingClient.PayoutRoute(UUID.randomUUID(), null, "SPEI", provider, account,
                BigDecimal.ZERO, null, priority, enabled);
    }

    private static DisbursementClient.Disbursement pago(UUID id) {
        return new DisbursementClient.Disbursement(id, UUID.randomUUID(), "credit-portfolio", "DISPOSITION",
                DISPO.toString(),
                Map.of("creditAccountId", CREDIT.toString(), "dispositionId", DISPO.toString()),
                "ROSA ELENA IBARRA SALGADO", "****0004", new BigDecimal("45000.00"), "MXN", "SPEI", "STP",
                "SETTLED", CLAVE, "https://stub.local/cep/" + CLAVE, null, null,
                Instant.parse("2026-10-30T07:22:29Z"), Instant.parse("2026-10-30T07:22:34Z"),
                Instant.parse("2026-10-30T07:23:08Z"));
    }

    private static StpClient.PaymentOrder ordenStp(UUID id) {
        return new StpClient.PaymentOrder(UUID.randomUUID(), id, UUID.randomUUID(), CLAVE,
                LocalDate.of(2026, 10, 30), new BigDecimal("45000.00"), "ROSA ELENA IBARRA SALGADO",
                "****0004", "****0012", "FINTECH SERVICES SA DE CV SOFOM ENR", "SETTLED", "1793335338448",
                "https://stub.local/cep/" + CLAVE, true, Instant.parse("2026-10-30T07:23:08Z"));
    }

    private static BankingClient.Trace traza(UUID id) {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-10-30T07:22:34Z");
        return new BankingClient.Trace(UUID.randomUUID(), "credit-portfolio", DISPO.toString(), CREDIT, id,
                ACCOUNT, CLAVE, null, null, new BigDecimal("45000.00"), LocalDate.of(2026, 10, 30),
                "SETTLED", false, "SIN_CONCILIAR",
                t0, t0, t0.plusSeconds(4), t0.plusSeconds(34), null, null);
    }

    // ── Cuentas y rutas ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("la cuenta sale con el contrato de la consola y el proveedor de su ruta de mayor prioridad")
    void bankAccountsMapToTheConsoleContract() {
        given(banking.bankAccounts()).willReturn(List.of(cuenta(ACCOUNT)));
        given(banking.payoutRoutes()).willReturn(List.of(
                ruta(ACCOUNT, "OTRO", 950, true),
                ruta(ACCOUNT, "STP", 900, true),
                ruta(ACCOUNT, "APAGADA", 1, false)));

        var cuentas = controller.bankAccounts();

        assertThat(cuentas).singleElement().satisfies(c -> {
            assertThat(c.bankAccountId()).isEqualTo(ACCOUNT);
            assertThat(c.bankName()).isEqualTo("STP");
            assertThat(c.bankCode()).isEqualTo("646");
            assertThat(c.clabeMasked()).isEqualTo("****0012");
            assertThat(c.ledgerAccountCode()).isEqualTo("1101");
            // Banking guarda la cuenta contable por código y contabilidad no expone su catálogo.
            assertThat(c.ledgerAccountName()).isNull();
            assertThat(c.status()).isEqualTo("ACTIVE");
            // Gana la ruta ACTIVA de menor `priority`; la apagada no cuenta aunque sea la primera.
            assertThat(c.provider()).isEqualTo("STP");
        });
    }

    @Test
    @DisplayName("si las rutas no contestan, las cuentas salen igual — sin proveedor")
    void bankAccountsSurviveFailingRoutes() {
        given(banking.bankAccounts()).willReturn(List.of(cuenta(ACCOUNT)));
        given(banking.payoutRoutes()).willThrow(new IllegalStateException("caído"));

        assertThat(controller.bankAccounts()).singleElement()
                .satisfies(c -> assertThat(c.provider()).isNull());
    }

    @Test
    @DisplayName("una ruta se lee con sus nombres de la consola: routeId y active")
    void payoutRoutesMapToTheConsoleContract() {
        var r = ruta(ACCOUNT, "STP", 900, true);
        given(banking.payoutRoutes()).willReturn(List.of(r));

        assertThat(controller.payoutRoutes()).singleElement().satisfies(v -> {
            assertThat(v.routeId()).isEqualTo(r.payoutRouteId());
            assertThat(v.rail()).isEqualTo("SPEI");
            assertThat(v.provider()).isEqualTo("STP");
            assertThat(v.minAmount()).isEqualByComparingTo("0");
            assertThat(v.maxAmount()).isNull();
            assertThat(v.priority()).isEqualTo(900);
            assertThat(v.active()).isTrue();
        });
    }

    // ── Pagos ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el listado lo resuelve disbursement: el BFF pasa los filtros y traduce cada fila")
    void theListIsResolvedByTheOwner() {
        UUID id = UUID.randomUUID();
        given(disbursements.search(CREDIT, "rosa", 0, 20)).willReturn(
                new DisbursementClient.DisbursementPage(List.of(pago(id)), 0, 20, 1, 1));

        var page = controller.disbursements(CREDIT, "rosa", 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(r -> {
            assertThat(r.disbursementId()).isEqualTo(id);
            assertThat(r.trackingKey()).isEqualTo(CLAVE);
            assertThat(r.beneficiaryName()).isEqualTo("ROSA ELENA IBARRA SALGADO");
            assertThat(r.beneficiaryClabeMasked()).isEqualTo("****0004");
            assertThat(r.creditAccountId()).isEqualTo(CREDIT);
            assertThat(r.dispositionId()).isEqualTo(DISPO);
            assertThat(r.cepUrl()).isEqualTo("https://stub.local/cep/" + CLAVE);
            assertThat(r.settledAt()).isEqualTo(Instant.parse("2026-10-30T07:23:08Z"));
        });
        verifyNoInteractions(banking, stp);
    }

    @Test
    @DisplayName("una página fuera de rango se acota antes de cruzar la red")
    void thePageIsClampedBeforeCallingTheOwner() {
        given(disbursements.search(null, null, 0, 200))
                .willReturn(new DisbursementClient.DisbursementPage(List.of(), 0, 200, 0, 0));

        controller.disbursements(null, null, -3, 5000);

        verify(disbursements).search(null, null, 0, 200);
    }

    @Test
    @DisplayName("la ficha suma pago, bitácora, orden STP y traza — una llamada por fuente")
    void theDetailComposesOneCallPerSource() {
        UUID id = UUID.randomUUID();
        given(disbursements.findById(id)).willReturn(pago(id));
        given(disbursements.timeline(id)).willReturn(List.of(
                new DisbursementClient.DisbursementEvent(UUID.randomUUID(), "ACCEPTED", "SETTLED", null,
                        "nombreCoincide=true", "PROVIDER", Instant.parse("2026-10-30T07:24:15Z")),
                new DisbursementClient.DisbursementEvent(UUID.randomUUID(), null, "REQUESTED", null,
                        "origen=credit-portfolio/DISPOSITION", "SYSTEM", Instant.parse("2026-10-30T07:22:29Z"))));
        given(stp.paymentOrder(id)).willReturn(Optional.of(ordenStp(id)));
        given(banking.traceByPayout(id)).willReturn(Optional.of(traza(id)));

        var ficha = controller.disbursement(id);

        assertThat(ficha.disbursement().disbursementId()).isEqualTo(id);
        // La bitácora en orden cronológico, aunque el dueño la devuelva en otro.
        assertThat(ficha.timeline()).extracting(TreasuryController.TimelineEntry::status)
                .containsExactly("REQUESTED", "SETTLED");
        assertThat(ficha.stpOrder().stpOrderId()).isEqualTo("1793335338448");
        assertThat(ficha.stpOrder().orderingClabeMasked()).isEqualTo("****0012");
        assertThat(ficha.stpOrder().orderingName()).isEqualTo("FINTECH SERVICES SA DE CV SOFOM ENR");
        assertThat(ficha.stpOrder().beneficiaryClabeMasked()).isEqualTo("****0004");

        var trace = ficha.movementTrace();
        assertThat(trace.complete()).isFalse();
        // Conciliar no tiene productor: la traza lo dice en vez de fingirse completa.
        assertThat(trace.missingLink()).isEqualTo("SIN_CONCILIAR");
        assertThat(trace.steps()).extracting(TreasuryController.TraceStep::step)
                .containsExactly("REQUESTED", "ROUTED", "DISPATCHED", "SETTLED", "RECONCILED", "POSTED");
        assertThat(trace.steps()).extracting(TreasuryController.TraceStep::status)
                .containsExactly("DONE", "DONE", "DONE", "DONE", "PENDING", "PENDING");
        assertThat(trace.steps().get(2).reference()).isEqualTo(CLAVE);
        assertThat(trace.steps().get(4).occurredAt()).isNull();

        verify(disbursements, times(1)).findById(id);
        verify(disbursements, times(1)).timeline(id);
        verify(stp, times(1)).paymentOrder(id);
        verify(banking, times(1)).traceByPayout(id);
    }

    @Test
    @DisplayName("si stp y banking no contestan, la ficha sale con el pago y su bitácora")
    void failingSourcesDegradeOnlyTheirBlocks() {
        UUID id = UUID.randomUUID();
        given(disbursements.findById(id)).willReturn(pago(id));
        given(disbursements.timeline(id)).willThrow(new IllegalStateException("caído"));
        given(stp.paymentOrder(id)).willThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "stp"));
        given(banking.traceByPayout(id)).willThrow(new IllegalStateException("caído"));

        var ficha = controller.disbursement(id);

        assertThat(ficha.disbursement()).isNotNull();
        assertThat(ficha.timeline()).isNull();
        assertThat(ficha.stpOrder()).isNull();
        assertThat(ficha.movementTrace()).isNull();
    }

    @Test
    @DisplayName("un pago que no salió por STP o sin traza todavía: esos bloques vienen nulos, no vacíos inventados")
    void absentBlocksAreNull() {
        UUID id = UUID.randomUUID();
        given(disbursements.findById(id)).willReturn(pago(id));
        given(disbursements.timeline(id)).willReturn(List.of());
        given(stp.paymentOrder(id)).willReturn(Optional.empty());
        given(banking.traceByPayout(id)).willReturn(Optional.empty());

        var ficha = controller.disbursement(id);

        assertThat(ficha.timeline()).isEmpty();
        assertThat(ficha.stpOrder()).isNull();
        assertThat(ficha.movementTrace()).isNull();
    }

    @Test
    @DisplayName("un pago inexistente es 404: sin pago no hay ficha")
    void aMissingDisbursementIsNotFound() {
        UUID id = UUID.randomUUID();
        given(disbursements.findById(id)).willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "no existe"));

        assertThatThrownBy(() -> controller.disbursement(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(stp, never()).paymentOrder(any());
    }

    @Test
    @DisplayName("si disbursement se cae, la orden STP y la traza siguen: se piden por el mismo id")
    void aFailingDisbursementDegradesOnlyItsBlock() {
        UUID id = UUID.randomUUID();
        given(disbursements.findById(id)).willThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "caído"));
        given(disbursements.timeline(id)).willThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "caído"));
        given(stp.paymentOrder(id)).willReturn(Optional.of(ordenStp(id)));
        given(banking.traceByPayout(id)).willReturn(Optional.of(traza(id)));

        var ficha = controller.disbursement(id);

        assertThat(ficha.disbursement()).isNull();
        assertThat(ficha.stpOrder()).isNotNull();
        assertThat(ficha.movementTrace()).isNotNull();
    }

    @Test
    @DisplayName("varias fichas a la vez: cada una compone SU pago, sin cruzarse")
    void concurrentDetailsKeepTheirOwnBlocks() throws Exception {
        int fichas = 8;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < fichas; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            given(disbursements.findById(id)).willReturn(pago(id));
            given(disbursements.timeline(id)).willReturn(List.of());
            given(stp.paymentOrder(id)).willReturn(Optional.of(ordenStp(id)));
            given(banking.traceByPayout(id)).willReturn(Optional.of(traza(id)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(fichas);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<TreasuryController.DisbursementDetailView>> tareas = new ArrayList<>();
        for (UUID id : ids) {
            tareas.add(pool.submit(() -> {
                salida.await();
                return controller.disbursement(id);
            }));
        }
        salida.countDown();
        for (int i = 0; i < fichas; i++) {
            var ficha = tareas.get(i).get(10, TimeUnit.SECONDS);
            assertThat(ficha.disbursement().disbursementId()).isEqualTo(ids.get(i));
            assertThat(ficha.movementTrace().steps().get(0).status()).isEqualTo("DONE");
        }
        pool.shutdown();
    }
}
