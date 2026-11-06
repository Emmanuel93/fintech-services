package com.fintech.receipt.application.service.assembly;

import com.fintech.documents.DocumentModel;
import com.fintech.receipt.application.ReceiptProperties;
import com.fintech.receipt.support.ReceiptSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptAssemblerTest {

    static final ZoneId CDMX = ZoneId.of("America/Mexico_City");
    final ReceiptAssembler assembler = new ReceiptAssembler();

    @Test
    @DisplayName("el total es la suma de sus cuotas sin descuento; cada préstamo numerado con su pago k de m")
    void theTotalIsHisInstallmentsWithoutHerDiscount() {
        ReceiptInputs in = ReceiptSamples.inputs(false);
        DocumentModel m = assembler.assemble(in, new ReceiptProperties(), CDMX);

        assertThat(in.totalDue()).isEqualByComparingTo("1337.45");
        assertThat(m.parameter("BOX1_VALUE")).isEqualTo("$1,337.45");
        assertThat(m.parameter("BOX2_VALUE")).isEqualTo("3 nov 2025");
        assertThat(m.parameter("BOX3_VALUE")).isEqualTo("2 vigentes");
        List<Map<String, Object>> rows = m.rows().stream().map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)).toList();
        assertThat(rows.stream().map(r -> r.get("c1")).toList()).containsExactly("1", "2", "Total a pagar en esta quincena", "3");
        assertThat(rows.get(0)).containsEntry("c4", "9 de 12").containsEntry("c8", "$972.61")
                .containsEntry("c10", "1 desde 18 oct 2025").containsEntry("emphasis", "1");
        assertThat(rows.get(1)).containsEntry("c4", "3 de 12").containsEntry("c8", "$364.84").containsEntry("c10", "—");
        assertThat(rows.get(2)).containsEntry("emphasis", "TOTAL").containsEntry("c8", "$1,337.45");
        assertThat(rows.get(3)).containsEntry("section", "LIQ").containsEntry("c5", "15 oct 2025");
    }

    @Test
    @DisplayName("cómo pagar: la CLABE de cobro de ella, a nombre de Kredius, con la referencia de él; tipo, folio y quién le presta")
    void howToPayTypeAndWhoHeOwes() {
        ReceiptInputs in = ReceiptSamples.inputs(false);
        DocumentModel m = assembler.assemble(in, new ReceiptProperties(), CDMX);

        assertThat(m.parameter("PAY_CLABE")).isEqualTo("646180157000000004");
        assertThat(m.parameter("PAY_REFERENCE")).isEqualTo("1234566");
        // Her collection CLABE is held by Kredius, as her statement says: the client deposits to that holder.
        assertThat(m.parameter("PAY_BENEFICIARY")).isEqualTo(new ReceiptProperties().getIssuer().getLegalName())
                .isNotEqualTo("Laura Méndez Ortiz");
        assertThat((String) m.parameter("PAY_NOTE")).contains("CLABE de cobro de tu distribuidora");
        assertThat(m.parameter("CLIENT_TYPE_LABEL")).isEqualTo("Cliente revolvente · 2 préstamos pagados");
        assertThat(m.parameter("RECEIPT_FOLIO")).isEqualTo("RC-20251021-1234566");
        assertThat((String) m.parameter("LEGAL_RECEIPT"))
                .contains("La obligación de tu deuda es con tu distribuidora, Laura Méndez Ortiz")
                .contains("puede marcarte para que otras distribuidoras conozcan tu comportamiento de pago");
        assertThat(m.parameter("RED_LIST_WARNING")).isNull();
    }

    @Test
    @DisplayName("si está en lista roja, el recibo lo advierte y dice qué la quita")
    void theRedListWarning() {
        DocumentModel m = assembler.assemble(ReceiptSamples.inputs(true), new ReceiptProperties(), CDMX);
        assertThat((String) m.parameter("RED_LIST_WARNING")).startsWith("Tienes una marca en tu historial de pagos con Kredius (tienes cuotas vencidas)")
                .contains("no puedes recibir préstamos nuevos");
        assertThat(ReceiptAssembler.typeLabel("NUEVO", 0)).isEqualTo("Cliente nuevo");
        assertThat(ReceiptAssembler.typeLabel(null, 0)).isNull();
    }
}
