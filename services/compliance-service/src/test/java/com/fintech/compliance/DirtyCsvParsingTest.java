package com.fintech.compliance;

import com.fintech.compliance.domain.CsvLayout;
import com.fintech.compliance.domain.WatchlistCsvParser;
import com.fintech.compliance.domain.WatchlistEntry;
import com.fintech.compliance.domain.WatchlistKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El parser contra lo que de verdad publica un gobierno.
 *
 * <p>Lo que estas pruebas protegen es una promesa concreta: <b>una fila mala no tumba la carga</b>.
 * Un parser que aborta al primer defecto no deja lista, y no tener lista significa que todo el mundo
 * sale limpio — que es exactamente lo que el control existe para impedir.
 *
 * <p>Y la contraria, igual de importante: un archivo del que no salió nada <b>no</b> es una lista
 * vacía. Esa distinción la fija {@link WatchlistLoadingTest}.
 */
class DirtyCsvParsingTest {

    private static final UUID VERSION = UUID.randomUUID();

    private static CsvLayout layout(char delimiter, Charset charset) {
        return new CsvLayout(delimiter, charset,
                Map.of(CsvLayout.COL_NAME, "NOMBRE",
                       CsvLayout.COL_CURP, "CURP",
                       CsvLayout.COL_DOB,  "FECHA_NACIMIENTO",
                       CsvLayout.COL_PROGRAM, "PROGRAMA"),
                List.of("yyyy-MM-dd", "dd/MM/yyyy"));
    }

    private record Resultado(List<WatchlistEntry> aceptadas,
                             List<WatchlistCsvParser.Rejection> rechazadas, int leidas) {}

    private static Resultado parse(String csv, CsvLayout layout) throws Exception {
        List<WatchlistEntry> ok = new ArrayList<>();
        List<WatchlistCsvParser.Rejection> malas = new ArrayList<>();
        int leidas = new WatchlistCsvParser(layout).parse(
                new ByteArrayInputStream(csv.getBytes(layout.charset())),
                VERSION, WatchlistKind.SANCTIONS,
                (e, line) -> ok.add(e), malas::add);
        return new Resultado(ok, malas, leidas);
    }

    // ── Lo que no debe tumbar la carga ───────────────────────────────────────────────────────

    @Test
    @DisplayName("una línea truncada se rechaza con su número; las demás siguen cargando")
    void aTruncatedLineDoesNotStopTheLoad() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                JUAN PEREZ,PEJU800101HDFXXX01,1980-01-01,SDN
                ANA LOPEZ
                ROSA MARTINEZ,MARO750202MDFXXX02,1975-02-02,SDN
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        // La truncada sí carga: trae nombre, que es lo único obligatorio. Lo demás queda nulo.
        assertThat(r.aceptadas()).hasSize(3);
        assertThat(r.aceptadas().get(1).getCurp()).isNull();
        assertThat(r.rechazadas()).isEmpty();
    }

    @Test
    @DisplayName("una fecha imposible NO descarta la fila: el nombre sigue sirviendo para cruzar")
    void anImpossibleDateKeepsTheRow() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                JUAN PEREZ,PEJU800101HDFXXX01,00/00/0000,SDN
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(1);
        assertThat(r.aceptadas().get(0).getDateOfBirth()).isNull();
        assertThat(r.rechazadas()).isEmpty();
    }

    @Test
    @DisplayName("dos formatos de fecha en el MISMO archivo, que es lo que llega")
    void twoDateFormatsInOneFile() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                JUAN PEREZ,,1980-01-15,SDN
                ANA LOPEZ,,15/01/1980,SDN
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(2);
        assertThat(r.aceptadas().get(0).getDateOfBirth())
                .isEqualTo(r.aceptadas().get(1).getDateOfBirth());
    }

    @Test
    @DisplayName("un campo entrecomillado con el separador dentro no parte la fila")
    void quotedDelimiterDoesNotSplitTheRow() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                "GARCIA, JUAN",,1980-01-01,"DIRECTOR GENERAL, ORGANISMO ESTATAL"
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(1);
        assertThat(r.aceptadas().get(0).getProgram()).isEqualTo("DIRECTOR GENERAL, ORGANISMO ESTATAL");
    }

    // ── Lo que sí se rechaza, y con motivo ───────────────────────────────────────────────────

    @Test
    @DisplayName("una fila sin nombre se rechaza diciendo la línea y el motivo")
    void aRowWithoutANameIsRejectedWithItsLine() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                JUAN PEREZ,,1980-01-01,SDN
                ,SINNOMBRE01,1975-02-02,SDN
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(1);
        assertThat(r.rechazadas()).singleElement().satisfies(x -> {
            assertThat(x.line()).isEqualTo(3);        // la cabecera es la 1
            assertThat(x.reason()).contains("sin nombre");
        });
    }

    @Test
    @DisplayName("el relleno («---», «N/D») se rechaza: cargarlo haría coincidir a cualquiera")
    void fillerRowsAreRejected() throws Exception {
        String csv = """
                NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA
                ---,,,
                N/D,,,
                JUAN PEREZ,,1980-01-01,SDN
                """;
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(2);   // «N/D» deja el token «N D», que sí es utilizable
        assertThat(r.rechazadas()).singleElement().satisfies(x ->
                assertThat(x.reason()).contains("ningún token utilizable"));
    }

    // ── Los dos fallos silenciosos que cuestan horas ─────────────────────────────────────────

    @Test
    @DisplayName("el BOM de UTF-8 no deja irreconocible la primera cabecera")
    void theBomDoesNotHideTheFirstHeader() throws Exception {
        // Sin quitarlo, la columna se llama "﻿NOMBRE" y no la encuentra nadie: la carga falla
        // entera diciendo que el archivo no trae nombre, cuando sí lo trae.
        String csv = "﻿NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA\nJUAN PEREZ,,1980-01-01,SDN\n";
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(1);
    }

    @Test
    @DisplayName("Latin-1 leído como Latin-1 conserva la Ñ; leído como UTF-8 la pierde en silencio")
    void latin1MustBeDeclared() throws Exception {
        String csv = "NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA\nJOSÉ MUÑOZ PEÑA,,1980-01-01,SDN\n";

        // Declarado bien: normaliza a MUNOZ PENA y cruza con quien se busca.
        Resultado bien = parse(csv, layout(',', StandardCharsets.ISO_8859_1));
        assertThat(bien.aceptadas().get(0).getNormalizedName()).isEqualTo("JOSE MUNOZ PENA");

        // Declarado mal, NO revienta — y eso es lo peligroso: produce un nombre que no es de nadie.
        byte[] latin1 = csv.getBytes(StandardCharsets.ISO_8859_1);
        List<WatchlistEntry> ok = new ArrayList<>();
        new WatchlistCsvParser(layout(',', StandardCharsets.UTF_8))
                .parse(new ByteArrayInputStream(latin1), VERSION, WatchlistKind.SANCTIONS,
                        (e, l) -> ok.add(e), rj -> {});

        assertThat(ok).hasSize(1);
        assertThat(ok.get(0).getNormalizedName())
                .as("por eso el encoding se DECLARA en el layout y no se adivina")
                .isNotEqualTo("JOSE MUNOZ PENA");
    }

    @Test
    @DisplayName("si la cabecera no trae la columna de nombre, la carga falla en vez de quedar vacía")
    void aMissingNameColumnFailsLoudly() {
        // Es la señal de que el layout ya no corresponde al archivo. Seguir produciría una lista
        // vacía, y una lista vacía deja pasar a todo el mundo.
        String csv = "CONTRIBUYENTE,RFC\nJUAN PEREZ,PEJU800101XX1\n";

        assertThatThrownBy(() -> parse(csv, layout(',', StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no trae la columna de nombre");
    }

    @Test
    @DisplayName("las líneas en blanco no son filas malas, son nada")
    void blankLinesAreNotRejections() throws Exception {
        String csv = "NOMBRE,CURP,FECHA_NACIMIENTO,PROGRAMA\nJUAN PEREZ,,1980-01-01,SDN\n\n\n";
        Resultado r = parse(csv, layout(',', StandardCharsets.UTF_8));

        assertThat(r.aceptadas()).hasSize(1);
        assertThat(r.rechazadas()).isEmpty();
        assertThat(r.leidas()).isEqualTo(1);
    }

    @Test
    @DisplayName("un separador distinto se declara y funciona igual")
    void anotherDelimiterJustWorks() throws Exception {
        String csv = "NOMBRE|CURP|FECHA_NACIMIENTO|PROGRAMA\nJUAN PEREZ||1980-01-01|69-B\n";
        Resultado r = parse(csv, layout('|', StandardCharsets.ISO_8859_1));

        assertThat(r.aceptadas()).singleElement()
                .satisfies(e -> assertThat(e.getProgram()).isEqualTo("69-B"));
    }
}
