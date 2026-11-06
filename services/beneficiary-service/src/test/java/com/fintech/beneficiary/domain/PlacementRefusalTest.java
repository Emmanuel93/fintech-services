package com.fintech.beneficiary.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementRefusalTest {

    @Test
    @DisplayName("cada límite del cliente se dice con sus números, en palabras de la distribuidora")
    void eachLimitInHerWords() {
        assertThat(PlacementRefusal.inHerWords("AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 5000 por préstamo, pidió 8000", true))
                .isEqualTo("A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo");
        assertThat(PlacementRefusal.inHerWords("CLIENT_OUTSTANDING_LIMIT: cliente REVOLVENTE hasta 15000 de saldo, debe 12000.50 y pidió 4000", true))
                .isEqualTo("Tu cliente ya te debe $12,000.50: a un cliente revolvente le puedes prestar hasta $15,000.00 sumando sus préstamos");
        assertThat(PlacementRefusal.inHerWords("CLIENT_ACTIVE_LOANS_LIMIT: cliente NUEVO hasta 1 préstamos vivos, ya tiene 1", true))
                .isEqualTo("Un cliente nuevo puede tener hasta 1 préstamo vivo contigo y ya tiene 1");
        assertThat(PlacementRefusal.inHerWords("TERM_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 16 quincenas, pidió 18", true))
                .isEqualTo("A un cliente nuevo le puedes prestar a lo más a 16 quincenas");
    }

    @Test
    @DisplayName("la lista roja se explica sólo si las marcas son suyas; si no, Kredius no le presta y nada más")
    void theRedListIsExplainedOnlyWhenTheMarksAreHers() {
        assertThat(PlacementRefusal.inHerWords("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT, VOUCHER_IN_ARREARS", true))
                .isEqualTo("Tu cliente está en lista roja (lo reportaste y tiene cuotas vencidas): por ahora no se le puede prestar");
        assertThat(PlacementRefusal.inHerWords("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT", false))
                .isEqualTo(PlacementRefusal.GENERIC_RED_LIST);
    }

    @Test
    @DisplayName("varios motivos se juntan; en lista roja sólo se dice la lista roja: un préstamo menor tampoco pasaría")
    void severalReasonsAreJoined_butTheRedListAloneIsSaid() {
        assertThat(PlacementRefusal.inHerWords("CLIENT_ACTIVE_LOANS_LIMIT: cliente NUEVO hasta 1 préstamos vivos, ya tiene 1; "
                + "CLIENT_OUTSTANDING_LIMIT: cliente NUEVO hasta 3000 de saldo, debe 2125.40 y pidió 2000", true))
                .isEqualTo("Un cliente nuevo puede tener hasta 1 préstamo vivo contigo y ya tiene 1. "
                        + "Tu cliente ya te debe $2,125.40: a un cliente nuevo le puedes prestar hasta $3,000.00 sumando sus préstamos");
        assertThat(PlacementRefusal.inHerWords("CLIENT_RED_LISTED: DISTRIBUTOR_IN_ARREARS; TERM_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 16 quincenas, pidió 24; "
                + "CLIENT_ACTIVE_LOANS_LIMIT: cliente NUEVO hasta 1 préstamos vivos, ya tiene 1", true))
                .isEqualTo("Tu cliente está en lista roja (tu línea tiene pagos vencidos): por ahora no se le puede prestar");
        assertThat(PlacementRefusal.inHerWords("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT; AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 3000 por préstamo, pidió 5000", false))
                .isEqualTo(PlacementRefusal.GENERIC_RED_LIST);
    }

    @Test
    @DisplayName("lo que debe se dice sólo si debe algo: sin deuda, el tope por préstamo ya lo dijo")
    void whatHeOwesIsSaidOnlyWhenHeOwes() {
        assertThat(PlacementRefusal.inHerWords("AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 3000 por préstamo, pidió 5000; "
                + "CLIENT_OUTSTANDING_LIMIT: cliente NUEVO hasta 3000 de saldo, debe 0 y pidió 5000", true))
                .isEqualTo("A un cliente nuevo le puedes prestar hasta $3,000.00 por préstamo");
        assertThat(PlacementRefusal.inHerWords("CLIENT_OUTSTANDING_LIMIT: cliente NUEVO hasta 3000 de saldo, debe 0.00 y pidió 3500", true))
                .isEqualTo("A un cliente nuevo le puedes prestar hasta $3,000.00 sumando sus préstamos");
        assertThat(PlacementRefusal.inHerWords(" ; ", true)).isEqualTo("Cartera no autorizó la colocación");
    }

    @Test
    void onlyClientReasonsCountAsAboutTheClient() {
        assertThat(PlacementRefusal.isAboutTheClient("AMOUNT_ABOVE_CLIENT_LIMIT: x")).isTrue();
        assertThat(PlacementRefusal.isAboutTheClient("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT")).isTrue();
        assertThat(PlacementRefusal.isAboutTheClient("AMOUNT_ABOVE_MAXIMUM: x")).isFalse();
        assertThat(PlacementRefusal.isAboutTheClient("LINE_BLOCKED_IN_ARREARS: x")).isFalse();
    }
}
