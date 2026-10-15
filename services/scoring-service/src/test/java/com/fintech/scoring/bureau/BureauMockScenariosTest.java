package com.fintech.scoring.bureau;

import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El mock del buró se comporta como el buró, incluido fallar.
 *
 * <p>Antes sólo sabía contestar bien: dos escenarios de negocio —limpio y rechazo— y
 * {@code status = SUCCESS} siempre. Con eso, «qué pasa si el buró no contesta» era una pregunta que
 * sólo se respondía en producción, y es justamente el camino que más importa: **el buró es el único
 * proveedor sin salida humana**, porque nadie puede inventar un puntaje.
 *
 * <p>El escenario se elige por el prefijo del CURP — el selector que este proveedor sí tiene. STP
 * usa los centavos del importe; aquí no hay importe, y el CURP viaja en cada consulta.
 */
class BureauMockScenariosTest {

    /** El adaptador es package-private a propósito: se instancia por reflexión para probarlo. */
    private static Object mock() throws Exception {
        Class<?> c = Class.forName(
                "com.fintech.scoring.infrastructure.adapter.out.circulo.MockCirculoAdapter");
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static CirculoReport query(String curp) throws Exception {
        Object adapter = mock();
        var m = adapter.getClass().getDeclaredMethod("query", UUID.class, UUID.class, UUID.class,
                CirculoQueryRequest.class);
        m.setAccessible(true);
        try {
            return (CirculoReport) m.invoke(adapter, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), request(curp));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static CirculoQueryRequest request(String curp) {
        return new CirculoQueryRequest("Ruiz", "Pérez", "Ana", curp, "RUPA900101AAA",
                LocalDate.of(1990, 1, 1), "Reforma", "100", null,
                "Centro", "Cuauhtémoc", "CDMX", "CDMX", "06000");
    }

    @Test
    @DisplayName("Camino feliz: reporte válido, y devuelve los identificadores que se le mandaron")
    void camino_feliz_devuelve_lo_que_recibio() throws Exception {
        CirculoReport r = query("RUPA900101HDFXXX01");

        assertThat(r.getStatus()).isEqualTo(CirculoReportStatus.SUCCESS);
        // Que el mock eco lo recibido es lo que hace que la traza cuadre de punta a punta:
        // un payload inventado rompe la correlación en cuanto alguien la sigue.
        assertThat(r.getPersonaNombres()).isEqualTo("Ana");
        assertThat(r.getPersonaApellidoPaterno()).isEqualTo("Ruiz");
    }

    @Test
    @DisplayName("🔑 El buró que NO contesta: lanza, y el prefetch queda FAILED")
    void el_buro_que_no_contesta_lanza() {
        // Es el escenario que no se podía probar, y el único sin salida humana: si el buró no
        // contesta, ningún analista puede suplir el dato.
        assertThatThrownBy(() -> query("ZZTO900101HDFXXX01"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no respondió");
    }

    @Test
    @DisplayName("El buró contesta que no pudo: ERROR, no una excepción")
    void el_buro_contesta_que_no_pudo() throws Exception {
        CirculoReport r = query("ZZER900101HDFXXX01");

        // Distinto del timeout a propósito: aquí el buró SÍ contestó, y lo que dijo fue que no
        // pudo. Colapsar los dos perdería la diferencia entre «está caído» y «te rechazó la
        // consulta», que se investigan distinto.
        assertThat(r.getStatus()).isEqualTo(CirculoReportStatus.ERROR);
        assertThat(r.getErrorMessage()).contains("ERROR");
    }

    @Test
    @DisplayName("Persona sin historial y persona no encontrada son estados distintos")
    void sin_historial_y_no_encontrada_se_distinguen() throws Exception {
        assertThat(query("ZZNH900101HDFXXX01").getStatus())
                .isEqualTo(CirculoReportStatus.NO_HIT);
        assertThat(query("ZZNF900101HDFXXX01").getStatus())
                .isEqualTo(CirculoReportStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("El rechazo por banda de FICO sigue funcionando")
    void el_rechazo_por_fico_sigue_ahi() throws Exception {
        CirculoReport r = query("XEXX010101HNEXXXA4");

        assertThat(r.getStatus()).isEqualTo(CirculoReportStatus.SUCCESS);
        assertThat(r.getFicoScoreValor()).isLessThan(600);
    }

    @Test
    @DisplayName("Determinista: el mismo CURP da el mismo puntaje, hoy y mañana")
    void el_mismo_curp_da_el_mismo_puntaje() throws Exception {
        // Sin esto, una prueba que pasa hoy falla mañana sin que nadie toque nada.
        assertThat(query("RUPA900101HDFXXX01").getFicoScoreValor())
                .isEqualTo(query("RUPA900101HDFXXX01").getFicoScoreValor());
    }
}
