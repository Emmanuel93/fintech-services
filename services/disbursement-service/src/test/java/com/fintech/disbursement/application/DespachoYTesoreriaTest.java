package com.fintech.disbursement.application;

import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.application.port.out.PayoutRouteResolverPort;
import com.fintech.disbursement.application.port.out.ProviderDispatchPort;
import com.fintech.disbursement.application.service.DisbursementDispatchService;
import com.fintech.disbursement.application.service.RoutingService;
import com.fintech.disbursement.domain.Beneficiary;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qué le pasa a una orden según lo que responda <b>tesorería</b>.
 *
 * <p>Son tres desenlaces y no dos, y confundir los dos últimos cuesta dinero real:
 *
 * <ol>
 *   <li>hay ruta → se despacha con la cuenta que tesorería eligió;</li>
 *   <li>no hay ruta → configuración incompleta: <b>gasta intento</b>, tiene que doler;</li>
 *   <li>tesorería no responde → indisponibilidad pasajera: <b>espera sin gastar intento</b>.</li>
 * </ol>
 *
 * <p>Si el tercero se tratara como el segundo, una caída de minutos agotaría los seis intentos de
 * órdenes perfectamente válidas y las dejaría {@code FAILED}, esperando a que alguien las mire.
 */
class DespachoYTesoreriaTest {

    private DisbursementOrderRepository orders;
    private ProviderDispatchPort conector;
    private DisbursementDispatchService servicio;
    private final AtomicReference<Optional<PayoutRouteResolverPort.PayoutRoute>> respuesta =
            new AtomicReference<>();
    private final AtomicReference<RuntimeException> falla = new AtomicReference<>();

    /** Una hora dentro de la ventana operativa de SPEI en cualquier configuración razonable. */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-26T15:00:00Z"), ZoneId.of("America/Mexico_City"));

    private static final PayoutRouteResolverPort.PayoutRoute RUTA =
            new PayoutRouteResolverPort.PayoutRoute(UUID.randomUUID(), "646180000000000012",
                    "FINTECH SA DE CV", "FSE200101AB1", "CLI-001", Provider.STP);

    private static DisbursementOrder orden() {
        return DisbursementOrder.request(
                UUID.randomUUID(), "credit-portfolio", DisbursementSource.DISPOSITION,
                "ref-1", "evt-" + UUID.randomUUID(), Map.of(),
                Beneficiary.of("JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646),
                new BigDecimal("1500.00"), "MXN", "DISPOSICION", 1L, Rail.SPEI, "corr-1");
    }

    @BeforeEach
    void init() {
        orders   = mock(DisbursementOrderRepository.class);
        conector = mock(ProviderDispatchPort.class);

        PayoutRouteResolverPort tesoreria = (companyId, rail, amount) -> {
            RuntimeException e = falla.get();
            if (e != null) throw e;
            return respuesta.get();
        };

        DisbursementProperties props = new DisbursementProperties();
        // La transacción no aporta nada aquí: se ejecuta el callback y ya.
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        servicio = new DisbursementDispatchService(orders,
                mock(DisbursementEventRepository.class), conector,
                mock(DisbursementEventPublisher.class),
                new RoutingService(tesoreria, props, RELOJ),
                props, tx, RELOJ);

        respuesta.set(Optional.of(RUTA));
        falla.set(null);
    }

    @Test
    @DisplayName("con ruta, la orden se despacha CON la cuenta que tesorería eligió")
    void conRuta() {
        DisbursementOrder o = orden();
        when(orders.lockDueForDispatch(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(o));

        servicio.dispatchDue();

        assertThat(o.status()).isEqualTo(DisbursementStatus.DISPATCHED);
        assertThat(o.getProvider()).isEqualTo(Provider.STP.name());
        // La cuenta viaja hasta el conector: sin ella tendría que resolverla contra su propia copia
        // del catálogo, que es de donde venimos.
        verify(conector).dispatch(o, RUTA);
    }

    @Test
    @DisplayName("SIN ruta la orden gasta intento — una configuración incompleta tiene que doler")
    void sinRuta() {
        respuesta.set(Optional.empty());
        DisbursementOrder o = orden();
        when(orders.lockDueForDispatch(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(o));

        servicio.dispatchDue();

        assertThat(o.getAttemptCount()).isEqualTo(1);
        assertThat(o.status()).isEqualTo(DisbursementStatus.REQUESTED);
        verify(conector, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("tesorería caída: la orden espera y NO gasta intento")
    void tesoreriaCaida() {
        falla.set(new PayoutRouteResolverPort.PayoutRoutingUnavailableException(
                "connection refused", new RuntimeException()));
        DisbursementOrder o = orden();
        when(orders.lockDueForDispatch(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(o));

        servicio.dispatchDue();

        // Es la diferencia entre "vuelvo en 30 segundos" y "seis intentos y muere".
        assertThat(o.getAttemptCount()).isZero();
        assertThat(o.status()).isEqualTo(DisbursementStatus.REQUESTED);
        assertThat(o.getScheduledFor()).isAfter(RELOJ.instant());
        verify(conector, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("una caída larga NO acaba matando la orden por acumulación de intentos")
    void caidaLargaNoMata() {
        falla.set(new PayoutRouteResolverPort.PayoutRoutingUnavailableException(
                "timeout", new RuntimeException()));
        DisbursementOrder o = orden();
        when(orders.lockDueForDispatch(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(o));

        // Diez pasadas del job con tesorería caída: más que los seis intentos que matan una orden.
        for (int i = 0; i < 10; i++) {
            servicio.dispatchDue();
        }

        assertThat(o.getAttemptCount()).isZero();
        assertThat(o.isTerminal()).isFalse();
    }
}
