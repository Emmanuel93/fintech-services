package com.fintech.statement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Una plantilla por producto; la forma de los datos, por familia. */
class StatementTemplateTest {

    /** El catálogo de credit-product: cada uno tiene que tener su estado de cuenta. */
    private static final List<String> PRODUCTOS = List.of("CREDIT_CARD", "REVOLVING_LINE", "DISTRIBUTOR_LINE",
            "BUSINESS_REVOLVING_LINE", "PERSONAL_LOAN", "PAYROLL_LOAN", "GROUP_LOAN", "MICRO_LOAN", "SME_LOAN");

    @Test
    @DisplayName("cada producto del catálogo tiene su plantilla, y cada plantilla su archivo")
    void cadaProductoTieneLaSuya() {
        for (String producto : PRODUCTOS) {
            assertThat(StatementTemplate.forProduct(producto)).as(producto).isPresent();
        }
        assertThat(Arrays.stream(StatementTemplate.values()).map(StatementTemplate::file).distinct().count())
                .as("ninguna plantilla compartida").isEqualTo(StatementTemplate.values().length);
        for (StatementTemplate t : StatementTemplate.values()) {
            assertThat(getClass().getResource("/reports/" + t.file() + ".jrxml")).as(t.file()).isNotNull();
        }
    }

    @Test
    @DisplayName("la forma de los datos sale de la familia del producto")
    void formaPorFamilia() {
        assertThat(StatementTemplate.PAYROLL_LOAN.layout()).isEqualTo(StatementLayout.INSTALLMENT);
        assertThat(StatementTemplate.SME_LOAN.layout()).isEqualTo(StatementLayout.INSTALLMENT);
        assertThat(StatementTemplate.CREDIT_CARD.layout()).isEqualTo(StatementLayout.REVOLVING);
        assertThat(StatementTemplate.BUSINESS_REVOLVING_LINE.layout()).isEqualTo(StatementLayout.REVOLVING);
        assertThat(StatementTemplate.DISTRIBUTOR_LINE.layout()).isEqualTo(StatementLayout.DISTRIBUTOR);
    }

    @Test
    @DisplayName("un producto sin plantilla no recibe una genérica; su documento se registra con la forma de su familia")
    void productoSinPlantilla() {
        assertThat(StatementTemplate.forProduct("STUDENT_LOAN")).isEmpty();
        assertThat(StatementTemplate.forProduct(null)).isEmpty();
        assertThat(StatementTemplate.forProduct("credit_card")).contains(StatementTemplate.CREDIT_CARD);

        assertThat(StatementLayout.of("STUDENT_LOAN", "CUSTOMER", "INSTALLMENT")).isEqualTo(StatementLayout.INSTALLMENT);
        assertThat(StatementLayout.of("NUEVA_LINEA", "DISTRIBUTOR", "REVOLVING")).isEqualTo(StatementLayout.DISTRIBUTOR);
        assertThat(StatementLayout.of("CREDIT_CARD", "CUSTOMER", "INSTALLMENT"))
                .as("con plantilla manda el producto").isEqualTo(StatementLayout.REVOLVING);
    }
}
