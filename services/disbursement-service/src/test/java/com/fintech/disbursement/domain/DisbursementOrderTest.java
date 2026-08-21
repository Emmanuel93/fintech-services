package com.fintech.disbursement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisbursementOrderTest {

    private static DisbursementOrder newOrder() {
        return DisbursementOrder.request(
                UUID.randomUUID(), "credit-portfolio", DisbursementSource.DISPOSITION,
                "ref-1", "evt-1", Map.of("dispositionId", "d-1"),
                Beneficiary.of("JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646),
                new BigDecimal("1500.00"), "MXN", "DISPOSICION", 1L, Rail.SPEI, "corr-1");
    }

    @Test
    @DisplayName("nace en REQUESTED, sin proveedor y sin intentos")
    void nace_en_requested() {
        DisbursementOrder order = newOrder();
        assertThat(order.status()).isEqualTo(DisbursementStatus.REQUESTED);
        assertThat(order.getProvider()).isNull();
        assertThat(order.getAttemptCount()).isZero();
        assertThat(order.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("el núcleo no guarda una sola referencia tipada al dominio de crédito")
    void procedencia_opaca() {
        DisbursementOrder order = newOrder();
        // Todo lo que sabe del emisor son cadenas que no interpreta.
        assertThat(order.getSourceReference()).isInstanceOf(String.class);
        assertThat(order.getSourceMetadata()).containsEntry("dispositionId", "d-1");
    }

    @Test
    @DisplayName("un monto no positivo no llega ni a existir")
    void rechaza_monto_no_positivo() {
        assertThatThrownBy(() -> DisbursementOrder.request(
                UUID.randomUUID(), "api", DisbursementSource.API, null, "evt", Map.of(),
                Beneficiary.of("X", "646180157000000004", "40", null, 646),
                BigDecimal.ZERO, "MXN", null, null, Rail.SPEI, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("DB-03 — ACCEPTED no es dinero entregado")
    class Aceptacion {

        @Test
        void accept_no_marca_liquidado() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            order.accept("STP-123");

            assertThat(order.status()).isEqualTo(DisbursementStatus.ACCEPTED);
            assertThat(order.getSettledAt()).isNull();
            assertThat(order.isTerminal()).isFalse();
        }

        @Test
        void settle_es_el_unico_camino_a_settled() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            order.accept("STP-123");
            Instant settledAt = Instant.parse("2026-08-11T18:00:00Z");
            order.settle("STP-123", "https://cep/x", settledAt);

            assertThat(order.status()).isEqualTo(DisbursementStatus.SETTLED);
            assertThat(order.getSettledAt()).isEqualTo(settledAt);
            assertThat(order.isTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("DB-01 — un estado terminal no se toca")
    class Terminalidad {

        @Test
        void no_se_puede_aceptar_una_orden_rechazada() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            order.reject(FailureCode.REJECTED_BY_PROVIDER.name(), "-200 RECHAZO_POR_PLD");

            assertThatThrownBy(() -> order.accept("STP-999"))
                    .isInstanceOf(InvalidDisbursementStateException.class);
        }

        @Test
        @DisplayName("salvo la devolución: SETTLED -> RETURNED sí es legítimo")
        void settled_puede_devolverse() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            order.accept("STP-123");
            order.settle("STP-123", "https://cep/x", Instant.now());

            order.markReturned("07");

            assertThat(order.status()).isEqualTo(DisbursementStatus.RETURNED);
            assertThat(order.getFailureCode())
                    .isEqualTo(FailureCode.RETURNED_BY_BENEFICIARY_BANK.name());
        }

        @Test
        void devolver_dos_veces_no_truena() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            order.settle("STP-123", null, Instant.now());
            order.markReturned("07");
            order.markReturned("07");

            assertThat(order.status()).isEqualTo(DisbursementStatus.RETURNED);
        }
    }

    @Nested
    @DisplayName("DB-08 — lo transitorio vuelve a la cola")
    class Reintentos {

        @Test
        void schedule_retry_no_gasta_intento_extra() {
            DisbursementOrder order = newOrder();
            order.dispatch(Provider.STP);
            assertThat(order.getAttemptCount()).isEqualTo(1);

            order.scheduleRetry("-30", "Enlace en modo consultas", Instant.now().plusSeconds(30));

            assertThat(order.status()).isEqualTo(DisbursementStatus.REQUESTED);
            assertThat(order.getAttemptCount()).isEqualTo(1);
            assertThat(order.getScheduledFor()).isNotNull();
        }

        @Test
        @DisplayName("un despacho que ni siquiera ocurrió sí gasta intento: la configuración incompleta tiene que doler")
        void defer_attempt_gasta_intento() {
            DisbursementOrder order = newOrder();
            order.deferAttempt("NO_ROUTING_RULE", "sin regla", Instant.now().plusSeconds(15));

            assertThat(order.getAttemptCount()).isEqualTo(1);
            assertThat(order.status()).isEqualTo(DisbursementStatus.REQUESTED);
        }
    }

    @Test
    @DisplayName("cancelar sólo antes de despachar")
    void cancelacion() {
        DisbursementOrder order = newOrder();
        order.cancel("Alta duplicada por operación");
        assertThat(order.status()).isEqualTo(DisbursementStatus.CANCELLED);

        DisbursementOrder despachada = newOrder();
        despachada.dispatch(Provider.STP);
        assertThatThrownBy(() -> despachada.cancel("tarde"))
                .isInstanceOf(InvalidDisbursementStateException.class);
    }

    @Test
    @DisplayName("la cuenta del beneficiario nunca sale completa en toString")
    void no_filtra_pii() {
        DisbursementOrder order = newOrder();
        assertThat(order.getBeneficiary().toString())
                .doesNotContain("646180157000000004")
                .contains("****0004");
    }
}
