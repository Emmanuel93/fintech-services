package com.fintech.banking.traceability;

import com.fintech.banking.application.port.out.CashMovementPublisher;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.fintech.banking.infrastructure.adapter.in.messaging.PayoutTraceListener;
import com.fintech.banking.infrastructure.adapter.in.messaging.StpOrderSettledListener;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un desembolso deja su traza, con Spring y Postgres de verdad.
 *
 * <p><b>La traza tenía servicio, tabla, controlador y pruebas, y cero productores.</b> Y la
 * proyección para conciliar descartaba todas las liquidaciones porque esperaba un importe que el
 * conector no mandaba: {@code internal_movements} y {@code movement_trace} estaban vacías con
 * pagos reales ya liquidados.
 *
 * <p>Kafka no participa: se llama al método del listener con el payload ya deserializado (el
 * contrato de deserialización lo fijan las pruebas de contrato). Lo que se prueba es la traducción
 * y el efecto en la base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayoutTraceFlowIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("banking"); }

    /** El seed de cuentas y rutas sobrevive al truncado (ver {@code AbstractIntegrationTest#preserve()}). */
    @Override protected List<String> preserve() { return List.of("bank_accounts", "payout_routes"); }

    @Autowired PayoutTraceListener payouts;
    @Autowired StpOrderSettledListener settlements;
    @Autowired TestRestTemplate rest;
    /** Kafka does not take part: the cash book's announcement is not what is tested here. */
    @MockBean CashMovementPublisher cashMovements;

    private static final UUID CUENTA = UUID.fromString("9b1d0000-0000-4000-8000-000000000001");
    private static final LocalDate DIA = LocalDate.of(2026, 10, 30);
    private static final BigDecimal IMPORTE = new BigDecimal("45000.00");

    private static PayoutTraceListener.PaymentRequestedPayload despacho(UUID pago) {
        return new PayoutTraceListener.PaymentRequestedPayload(pago, UUID.randomUUID(), IMPORTE, CUENTA,
                Instant.parse("2026-10-30T07:22:34Z"));
    }

    private static PayoutTraceListener.DisbursementAcceptedPayload aceptado(UUID pago, UUID credito, String clave) {
        return new PayoutTraceListener.DisbursementAcceptedPayload(pago, "credit-portfolio", "DISPOSITION",
                "disp-" + pago, Map.of("creditAccountId", credito.toString(), "dispositionId", "disp-" + pago),
                IMPORTE, clave, Instant.parse("2026-10-30T07:22:38Z"));
    }

    private static StpOrderSettledListener.StpOrderSettledPayload liquidado(UUID pago, String clave,
                                                                            BigDecimal importe, LocalDate dia) {
        return new StpOrderSettledListener.StpOrderSettledPayload(
                importe == null ? null : "settled-" + pago, pago, UUID.randomUUID(), clave, importe, dia, dia,
                Instant.parse("2026-10-30T07:23:08Z"), Instant.parse("2026-10-30T07:24:15Z"));
    }

    private static String clave() {
        return "KRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private static HttpHeaders comoTesoreria() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "tesoreria-1");
        h.set("X-Roles", "FINANCE");
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<List> porCredito(UUID credito) {
        return rest.exchange("/api/v1/traces/by-credit-account/" + credito, HttpMethod.GET,
                new HttpEntity<>(comoTesoreria()), List.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> porPago(UUID pago) {
        return rest.exchange("/api/v1/traces/by-payout/" + pago, HttpMethod.GET,
                new HttpEntity<>(comoTesoreria()), Map.class);
    }

    private int movimientosDe(UUID pago) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking.internal_movements WHERE reference = ?",
                Integer.class, pago.toString());
    }

    @Test
    @DisplayName("despacho → aceptación → liquidación: el pago queda proyectado y su traza consultable por crédito")
    void unDesembolsoDejaSuTraza() {
        UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
        String clave = clave();

        payouts.onPaymentRequested(despacho(pago));
        payouts.onDisbursementAccepted(aceptado(pago, credito, clave));
        settlements.onOrderSettled(liquidado(pago, clave, IMPORTE, DIA));

        // La proyección para conciliar: el cargo que el banco va a reportar.
        Map<String, Object> mov = jdbcTemplate.queryForMap(
                "SELECT * FROM banking.internal_movements WHERE reference = ?", pago.toString());
        assertThat(mov.get("movement_type")).isEqualTo("STP_ORDER");
        assertThat((BigDecimal) mov.get("amount")).isEqualByComparingTo(IMPORTE);
        assertThat(mov.get("tracking_key")).isEqualTo(clave);
        assertThat(mov.get("direction")).isEqualTo("DEBIT");
        assertThat(mov.get("business_date").toString()).isEqualTo("2026-10-30");

        // La traza, por la pregunta que hace tesorería — y con FINANCE, que antes recibía 403.
        var resp = porCredito(credito);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) resp.getBody().get(0);
        assertThat(t.get("payoutId")).isEqualTo(pago.toString());
        assertThat(t.get("bankAccountId")).isEqualTo(CUENTA.toString());
        assertThat(t.get("trackingKey")).isEqualTo(clave);
        assertThat(t.get("sourceSystem")).isEqualTo("credit-portfolio");
        assertThat(t.get("businessDate")).isEqualTo("2026-10-30");
        assertThat(t.get("status")).isEqualTo("SETTLED");
        assertThat(t.get("routedAt")).isNotNull();
        assertThat(t.get("dispatchedAt")).isNotNull();
        assertThat(t.get("settledAt")).isNotNull();
        // Honesto: conciliar y asentar no tienen productor todavía, y la traza lo dice.
        assertThat(t.get("complete")).isEqualTo(false);
        assertThat(t.get("missingLink")).isEqualTo("SIN_CONCILIAR");
        assertThat(t.get("reconciledAt")).isNull();
        assertThat(t.get("postedAt")).isNull();

        assertThat(porPago(pago).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("un pago rechazado termina FALLIDO con su motivo y deja de contar como traza a medias (D61)")
    void unPagoRechazadoTerminaFallido() {
        UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
        payouts.onPaymentRequested(despacho(pago));
        payouts.onDisbursementAccepted(aceptado(pago, credito, clave()));

        payouts.onDisbursementFailed(new PayoutTraceListener.DisbursementFailedPayload(pago, IMPORTE,
                "REJECTED_BY_PROVIDER", "-200 RECHAZO_POR_PLD — Se rechaza por PLD", Instant.parse("2026-10-30T07:23:00Z")));

        Map<?, ?> t = porPago(pago).getBody();
        assertThat(t.get("status")).isEqualTo("FAILED");
        assertThat((String) t.get("failureReason")).contains("REJECTED_BY_PROVIDER").contains("PLD");
        assertThat(movimientosDe(pago)).as("el dinero no salió: nada en el libro de caja").isZero();
        Integer incompletas = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM banking.movement_trace WHERE payout_id = ? AND status <> 'FAILED'", Integer.class, pago);
        assertThat(incompletas).isZero();
    }

    @Test
    @DisplayName("un fallo tardío no deshace una liquidación, y una devolución la cierra como DEVUELTA")
    void falloTardioYDevolucion() {
        UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
        String clave = clave();
        payouts.onPaymentRequested(despacho(pago));
        payouts.onDisbursementAccepted(aceptado(pago, credito, clave));
        settlements.onOrderSettled(liquidado(pago, clave, IMPORTE, DIA));

        payouts.onDisbursementFailed(new PayoutTraceListener.DisbursementFailedPayload(pago, IMPORTE,
                "REJECTED_BY_PROVIDER", "tarde", Instant.parse("2026-10-30T08:00:00Z")));
        assertThat(porPago(pago).getBody().get("status")).as("el dinero ya salió").isEqualTo("SETTLED");

        payouts.onDisbursementReturned(new PayoutTraceListener.DisbursementReturnedPayload(pago, IMPORTE, "R01",
                Instant.parse("2026-10-31T10:00:00Z")));
        assertThat(porPago(pago).getBody().get("status")).isEqualTo("RETURNED");
    }

    @Test
    @DisplayName("la liquidación reentregada no duplica el movimiento ni mueve la traza")
    void liquidacionReentregada() {
        UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
        String clave = clave();
        payouts.onPaymentRequested(despacho(pago));
        payouts.onDisbursementAccepted(aceptado(pago, credito, clave));

        settlements.onOrderSettled(liquidado(pago, clave, IMPORTE, DIA));
        settlements.onOrderSettled(liquidado(pago, clave, IMPORTE, DIA));

        assertThat(movimientosDe(pago)).isEqualTo(1);
        assertThat(porCredito(credito).getBody()).hasSize(1);
    }

    @Test
    @DisplayName("en desorden —la liquidación antes que el despacho— la traza converge igual")
    void enDesorden() {
        UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
        String clave = clave();

        settlements.onOrderSettled(liquidado(pago, clave, IMPORTE, DIA));
        payouts.onDisbursementAccepted(aceptado(pago, credito, clave));
        payouts.onPaymentRequested(despacho(pago));

        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) porCredito(credito).getBody().get(0);
        assertThat(t.get("bankAccountId")).isEqualTo(CUENTA.toString());
        assertThat(t.get("trackingKey")).isEqualTo(clave);
        // Un ruteo que llega último no regresa a ROUTED lo que ya se liquidó.
        assertThat(t.get("status")).isEqualTo("SETTLED");
    }

    @Test
    @DisplayName("un emisor viejo sin importe: no se inventa el cargo, pero la traza sí avanza")
    void sinImporteNoSeProyecta() {
        UUID pago = UUID.randomUUID();
        String clave = clave();
        payouts.onPaymentRequested(despacho(pago));

        settlements.onOrderSettled(liquidado(pago, clave, null, null));

        assertThat(movimientosDe(pago)).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> t = porPago(pago).getBody();
        assertThat(t.get("status")).isEqualTo("SETTLED");
        assertThat(t.get("trackingKey")).isEqualTo(clave);
        // Sin fecha de operación en el mensaje, la de la liquidación en hora del centro.
        assertThat(t.get("businessDate")).isEqualTo("2026-10-30");
    }

    @Test
    @DisplayName("el despacho y la aceptación del mismo pago a la vez: una sola traza, con los dos tramos")
    void hechosConcurrentesDelMismoPago() throws Exception {
        int rondas = 6;
        ExecutorService pool = Executors.newFixedThreadPool(rondas * 2);
        List<UUID> pagos = new ArrayList<>();
        List<UUID> creditos = new ArrayList<>();
        List<Future<?>> tareas = new ArrayList<>();
        CountDownLatch salida = new CountDownLatch(1);
        for (int i = 0; i < rondas; i++) {
            UUID pago = UUID.randomUUID(), credito = UUID.randomUUID();
            pagos.add(pago);
            creditos.add(credito);
            String clave = clave() + i;
            tareas.add(pool.submit(() -> { salida.await(); payouts.onPaymentRequested(despacho(pago)); return null; }));
            tareas.add(pool.submit(() -> { salida.await(); payouts.onDisbursementAccepted(aceptado(pago, credito, clave)); return null; }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        for (int i = 0; i < rondas; i++) {
            Integer filas = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM banking.movement_trace WHERE payout_id = ?", Integer.class, pagos.get(i));
            assertThat(filas).as("una sola traza por pago").isEqualTo(1);
            Map<String, Object> t = jdbcTemplate.queryForMap(
                    "SELECT * FROM banking.movement_trace WHERE payout_id = ?", pagos.get(i));
            // Ni el ruteo ni el origen se perdieron por escribir encima del otro.
            assertThat(t.get("bank_account_id")).isEqualTo(CUENTA);
            assertThat(t.get("credit_account_id")).isEqualTo(creditos.get(i));
            assertThat(t.get("tracking_key")).isNotNull();
            assertThat(t.get("status")).isEqualTo("DISPATCHED");
        }
    }
}
