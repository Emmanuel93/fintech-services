package com.fintech.disbursement.acl;

import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedListener;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedPayload;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Override protected List<String> esquemas() { return List.of("disbursement"); }

    /**
     * El mapeo de empresa sobrevive al truncado: lo siembra Liquibase y sin él DB-07 rechaza toda
     * orden. Es la trampa que documenta {@code AbstractIntegrationTest#preservar()}.
     */
    @Override protected List<String> preservar() { return List.of("company_mappings"); }

    @Autowired DispositionAuthorizedListener listener;
    @Autowired DisbursementOrderRepository orders;

    private static DispositionAuthorizedPayload autorizada(UUID dispositionId, String monto) {
        return new DispositionAuthorizedPayload(
                dispositionId, UUID.randomUUID(), null, "SUC-001", null,
                new BigDecimal(monto), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                1L, "DISPOSICION DE CREDITO", Instant.now());
    }

    @Test
    @DisplayName("una disposición autorizada crea la orden de pago con su beneficiario")
    void creaLaOrden() {
        UUID disposicion = UUID.randomUUID();

        listener.onDispositionAuthorized(autorizada(disposicion, "20000.00"));

        var orden = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposicion.toString());
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
        UUID disposicion = UUID.randomUUID();

        listener.onDispositionAuthorized(autorizada(disposicion, "5000.00"));
        listener.onDispositionAuthorized(autorizada(disposicion, "5000.00"));

        // Kafka entrega al menos una vez. Sin la restricción única, un reintegro pagaría dos veces.
        Integer cuantas = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM disbursement.disbursement_orders
                 WHERE source_event_id = ?
                """, Integer.class, disposicion.toString());
        assertThat(cuantas).isEqualTo(1);
    }

    @Test
    @DisplayName("una disposición sin monto no crea orden — y no revienta")
    void sinMontoNoHaceNada() {
        UUID disposicion = UUID.randomUUID();

        listener.onDispositionAuthorized(new DispositionAuthorizedPayload(
                disposicion, UUID.randomUUID(), null, "SUC-001", null, null, "MXN",
                "JUAN PEREZ", "646180157000000004", "40", null, 646, 1L, "X", Instant.now()));

        assertThat(orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposicion.toString())).isEmpty();
    }

    @Test
    @DisplayName("la procedencia viaja en eco y el núcleo NO la lee para decidir (DB-09)")
    void laProcedenciaViajaEnEco() {
        UUID disposicion = UUID.randomUUID();
        listener.onDispositionAuthorized(autorizada(disposicion, "7500.00"));

        var orden = orders.findBySourceKey("credit-portfolio",
                DisbursementSource.DISPOSITION.name(), disposicion.toString()).orElseThrow();

        // Es lo que permite que el emisor correlacione la respuesta sin que este servicio entienda
        // qué es una disposición. Borrar los tres listeners dejaría un motor de payouts intacto.
        assertThat(orden.getSourceMetadata()).containsEntry("dispositionId", disposicion.toString());
    }
}
