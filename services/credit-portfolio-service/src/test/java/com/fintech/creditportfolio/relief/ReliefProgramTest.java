package com.fintech.creditportfolio.relief;

import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El programa de apoyo: quién lo autoriza, a quién alcanza y qué se asume al otorgarlo.
 *
 * <p>Se implementa como <b>reestructura</b>. El camino alterno —un tratamiento contable especial—
 * depende de una autorización que puede no estar vigente cuando la contingencia ocurra, que es
 * exactamente cuando hay que actuar rápido.
 */
class ReliefProgramTest {

    private static final LocalDate CORTE   = LocalDate.of(2026, 5, 31);
    private static final LocalDate DESDE   = LocalDate.of(2026, 6, 1);
    private static final LocalDate HASTA   = LocalDate.of(2026, 8, 31);

    private static ReliefProgram program(String producto, Integer dpdMaximo) {
        return ReliefProgram.propose("Apoyo huracán Otis", "NATURAL_DISASTER", 3,
                DESDE, HASTA, producto, null, null, dpdMaximo, CORTE, "ACCRUES", "ana.riesgos");
    }

    @Test
    @DisplayName("maker-checker: quien propone NO autoriza")
    void quienProponeNoAutoriza() {
        ReliefProgram p = program(null, null);

        // Un programa mueve la fecha de pago de una cartera entera y sube la reserva. Que una sola
        // persona pueda hacerlo sin contraparte es lo primero que una revisión pregunta.
        assertThatThrownBy(() -> p.authorize("ana.riesgos"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maker-checker");

        p.authorize("luis.direccion");
        assertThat(p.isAuthorized()).isTrue();
        assertThat(p.getApprovedBy()).isEqualTo("luis.direccion");
    }

    @Test
    @DisplayName("no se autoriza dos veces ni se autoriza uno ya rechazado")
    void unaSolaVez() {
        ReliefProgram p = program(null, null);
        p.authorize("luis.direccion");

        assertThatThrownBy(() -> p.authorize("otro.mas"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sólo se autoriza uno PROPOSED");

        ReliefProgram rechazado = program(null, null);
        rechazado.reject("luis.direccion");
        assertThatThrownBy(() -> rechazado.authorize("otro.mas"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("la fecha de corte NO puede ser posterior al inicio de la vigencia")
    void theeligibilityCutoffComesFirst() {
        // Sin esto, un programa anunciado hoy alcanzaría a quien dejó de pagar al enterarse de que
        // venía: el apoyo taparía mora provocada por el propio anuncio.
        assertThatThrownBy(() -> ReliefProgram.propose("Apoyo", "SANITARY", 3,
                DESDE, HASTA, null, null, null, 30, DESDE.plusDays(1), "ACCRUES", "ana"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fecha de corte");
    }

    @Test
    @DisplayName("el filtro de DPD acota el padrón")
    void elDpdAcota() {
        ReliefProgram p = program(null, 30);

        assertThat(p.reaches("PERSONAL_LOAN", "SUC-001", null, 0)).isTrue();
        assertThat(p.reaches("PERSONAL_LOAN", "SUC-001", null, 30)).isTrue();
        // Al corriente o con atraso menor: el apoyo no es para rescatar carteras ya perdidas.
        assertThat(p.reaches("PERSONAL_LOAN", "SUC-001", null, 31)).isFalse();
    }

    @Test
    @DisplayName("los filtros nulos NO filtran, y se combinan con AND")
    void filtrosNulos() {
        ReliefProgram singleProduct = program("PAYROLL_LOAN", null);

        assertThat(singleProduct.reaches("PAYROLL_LOAN", "SUC-001", null, 999)).isTrue();
        assertThat(singleProduct.reaches("PERSONAL_LOAN", "SUC-001", null, 0)).isFalse();

        // Un programa sin criterios alcanza a toda la cartera. Es una decisión legítima, y por eso
        // alguien tiene que autorizarla a propósito.
        assertThat(program(null, null).reaches("LO_QUE_SEA", "SUC-999", null, 5000)).isTrue();
    }

    @Test
    @DisplayName("un programa que difiere CERO pagos no se puede crear")
    void ceroPeriodos() {
        assertThatThrownBy(() -> ReliefProgram.propose("Apoyo vacío", "OTHER", 0,
                DESDE, HASTA, null, null, null, null, CORTE, "ACCRUES", "ana"))
                .hasMessageContaining("no apoya a nadie");
    }

    @Test
    @DisplayName("la vigencia acota cuándo aplica")
    void laVigenciaAcota() {
        ReliefProgram p = program(null, null);

        assertThat(p.activeOn(DESDE)).isTrue();
        assertThat(p.activeOn(HASTA)).isTrue();
        assertThat(p.activeOn(DESDE.minusDays(1))).isFalse();
        // Al vencer la vigencia el calendario retoma y el DPD vuelve a correr.
        assertThat(p.activeOn(HASTA.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("ACCRUES devenga durante el apoyo; WAIVED lo condona")
    void elDevengoDuranteElApoyo() {
        assertThat(program(null, null).waivesAccrual()).isFalse();

        ReliefProgram condonado = ReliefProgram.propose("Apoyo con condonación", "SANITARY", 3,
                DESDE, HASTA, null, null, null, null, CORTE, "WAIVED", "ana");
        assertThat(condonado.waivesAccrual()).isTrue();
    }
}
