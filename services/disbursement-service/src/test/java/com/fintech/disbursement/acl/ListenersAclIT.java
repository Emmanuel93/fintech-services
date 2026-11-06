package com.fintech.disbursement.acl;

import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.FailureCode;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedListener;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedPayload;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Los listeners ACL, con Spring de verdad.
 *
 * <p><b>Este servicio tenía cero pruebas con contexto.</b> Sus tres listeners de entrada —la frontera
 * donde muere todo el vocabulario de crédito— no estaban probados, y son justo el punto donde un
 * cambio de contrato del productor se manifiesta: un campo que deja de llegar no rompe la
 * compilación, rompe el pago.
 *
 * <p>Se prueba el listener contra el caso de uso real y la base real. Lo único que no participa es
 * Kafka: llamar al método del listener con el payload deserializado prueba exactamente lo que hay
 * que probar —la traducción y el efecto— sin pagar un broker.
 */
@SpringBootTest
@ActiveProfiles("test")
class ListenersAclIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("disbursement"); }

    /**
     * El mapeo de empresa sobrevive al truncado: lo siembra Liquibase y sin él DB-07 rechaza toda
     * orden. Es la trampa que documenta {@code AbstractIntegrationTest#preserve()}.
     */
    @Override protected List<String> preserve() { return List.of("company_mappings"); }

    @Autowired DispositionAuthorizedListener listener;
    @Autowired DisbursementOrderRepository orders;
    /** Kafka does not take part: a rejection answered is what the publisher is asked to say. */
    @MockitoBean DisbursementEventPublisher publisher;

    private static DispositionAuthorizedPayload attempt(UUID dispositionId, String amount, int attempt, Instant notBefore) {
        return new DispositionAuthorizedPayload(
                dispositionId, UUID.randomUUID(), null, "SUC-001", null,
                new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                1L, "DISPOSICION DE CREDITO", attempt, notBefore, Instant.now());
    }

    private static DispositionAuthorizedPayload authorized(UUID dispositionId, String amount) {
        return new DispositionAuthorizedPayload(
                dispositionId, UUID.randomUUID(), null, "SUC-001", null,
                new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                1L, "DISPOSICION DE CREDITO", null, null, Instant.now());
    }

    @Test
    @DisplayName("una disposición autorizada crea la orden de pago con su beneficiario")
    void creaLaOrden() {
        UUID disposition = UUID.randomUUID();

        listener.onDispositionAuthorized(authorized(disposition, "20000.00"));

        var orden = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition.toString());
        assertThat(orden).isPresent();
        assertThat(orden.get().getAmount()).isEqualByComparingTo("20000.00");
        // Los tres campos que deciden adónde va el dinero. Si el productor deja de mandarlos, la
        // orden se crea sin destino o no se crea — y la disposición se queda PROCESSING para
        // siempre sin que nadie sepa por qué.
        assertThat(orden.get().getBeneficiary().getName()).isEqualTo("JUAN PEREZ");
        assertThat(orden.get().getBeneficiary().getAccount()).isEqualTo("646180157000000004");
        assertThat(orden.get().status()).isIn(DisbursementStatus.REQUESTED);
    }

    @Test
    @DisplayName("DB-02 · el mismo evento reentregado NO crea una segunda orden")
    void esIdempotente() {
        UUID disposition = UUID.randomUUID();

        listener.onDispositionAuthorized(authorized(disposition, "5000.00"));
        listener.onDispositionAuthorized(authorized(disposition, "5000.00"));

        // Kafka entrega al menos una vez. Sin la restricción única, un reintegro pagaría dos veces.
        Integer cuantas = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM disbursement.disbursement_orders
                 WHERE source_event_id = ?
                """, Integer.class, disposition.toString());
        assertThat(cuantas).isEqualTo(1);
    }

    @Test
    @DisplayName("D63 · otro intento de la misma disposición es otra orden, con su número en la llave y la espera que pide cartera")
    void cadaIntentoEsSuOrden() {
        UUID disposition = UUID.randomUUID();
        Instant later = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);

        listener.onDispositionAuthorized(authorized(disposition, "20000.00"));
        listener.onDispositionAuthorized(attempt(disposition, "20000.00", 2, later));

        var primera = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition.toString()).orElseThrow();
        var segunda = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition + ":2").orElseThrow();
        assertThat(segunda.getDisbursementId()).isNotEqualTo(primera.getDisbursementId());
        assertThat(segunda.getSourceReference()).isEqualTo(disposition.toString());
        assertThat(primera.getSourceMetadata()).containsEntry("attempt", "1");
        assertThat(segunda.getSourceMetadata()).containsEntry("attempt", "2")
                .containsEntry("dispositionId", disposition.toString());
        assertThat(segunda.getScheduledFor()).as("sale cuando cartera lo pidió, no antes").isEqualTo(later);
    }

    @Test
    @DisplayName("D67 · una CLABE inválida no se queda en la cola de muertos: se responde que falló, sin orden")
    void claveInvalidaSeResponde() {
        UUID disposition = UUID.randomUUID();

        listener.onDispositionAuthorized(new DispositionAuthorizedPayload(
                disposition, UUID.randomUUID(), null, "SUC-001", null, new BigDecimal("5000.00"), "MXN",
                "JUAN PEREZ", "646180157000000005", "40", null, 646, 1L, "X", null, null, Instant.now()));

        assertThat(orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition.toString())).isEmpty();
        verify(publisher).publishRequestRejected(
                argThat(c -> c.sourceEventId().equals(disposition.toString())
                        && "1".equals(c.metadataOrEmpty().get("attempt"))),
                eq(FailureCode.INVALID_BENEFICIARY_ACCOUNT), anyString());
    }

    @Test
    @DisplayName("una disposición sin monto no crea orden — y no revienta")
    void sinMontoNoHaceNada() {
        UUID disposition = UUID.randomUUID();

        listener.onDispositionAuthorized(new DispositionAuthorizedPayload(
                disposition, UUID.randomUUID(), null, "SUC-001", null, null, "MXN",
                "JUAN PEREZ", "646180157000000004", "40", null, 646, 1L, "X", null, null, Instant.now()));

        assertThat(orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition.toString())).isEmpty();
    }

    @Test
    @DisplayName("la procedencia viaja en eco y el núcleo NO la lee para decidir (DB-09)")
    void laProcedenciaViajaEnEco() {
        UUID disposition = UUID.randomUUID();
        listener.onDispositionAuthorized(authorized(disposition, "7500.00"));

        var orden = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposition.toString()).orElseThrow();

        // Es lo que permite que el emisor correlacione la respuesta sin que este servicio entienda
        // qué es una disposición. Borrar los tres listeners dejaría un motor de payouts intacto.
        assertThat(orden.getSourceMetadata()).containsEntry("dispositionId", disposition.toString());
    }
}
