package com.fintech.compliance.infrastructure.adapter.out.government;

import com.fintech.compliance.application.port.out.GovernmentListPort;
import com.fintech.compliance.domain.CsvLayout;
import com.fintech.compliance.domain.WatchlistKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * El publicador de listas mientras no hay uno de verdad.
 *
 * <h2>Entrega archivos SUCIOS a propósito</h2>
 *
 * Un mock que devuelve un CSV impecable no prueba nada: hace pasar un parser que se rompería con el
 * primer archivo real. Las publicaciones oficiales vienen con acentos inconsistentes, dos formatos
 * de fecha en el mismo archivo, líneas truncadas, comillas sin cerrar, filas de relleno («N/D»,
 * «---»), duplicados y —lo más caro de descubrir— <b>encoding Latin-1</b>.
 *
 * <p>Así que este generador reproduce exactamente eso. Si el parser aguanta esto, aguanta un archivo
 * real; si se rompe, se rompe aquí y no en producción con una lista de sancionados a medio cargar.
 *
 * <h2>Y es determinista</h2>
 *
 * La semilla sale del código de la lista y de la fecha de publicación, no del reloj. Dos llamadas
 * con los mismos argumentos producen <b>el mismo archivo byte a byte</b> — y por tanto el mismo
 * hash, que es lo que hace comprobable la idempotencia: pedir dos veces la lista del mismo día tiene
 * que no cargar nada la segunda vez.
 *
 * <p>Se registra con {@code @ConditionalOnMissingBean}: el día que exista el adaptador real, éste
 * desaparece sin tocar una línea de quien lo usa.
 */
@Component
@ConditionalOnMissingBean(name = "governmentListAdapter")
public class MockGovernmentListAdapter implements GovernmentListPort {

    private static final Logger log = LoggerFactory.getLogger(MockGovernmentListAdapter.class);

    /** Apellidos y nombres mexicanos frecuentes: la homonimia que producen es la del mundo real. */
    private static final List<String> PATERNOS = List.of(
            "HERNÁNDEZ", "GARCÍA", "MARTÍNEZ", "LÓPEZ", "GONZÁLEZ", "PÉREZ", "RODRÍGUEZ",
            "SÁNCHEZ", "RAMÍREZ", "FLORES", "MUÑOZ", "DE LA CRUZ", "PEÑA", "ÁVILA");
    private static final List<String> MATERNOS = List.of(
            "JIMÉNEZ", "MORALES", "REYES", "GUTIÉRREZ", "ORTIZ", "CHÁVEZ", "RAMOS",
            "DOMÍNGUEZ", "VÁZQUEZ", "CASTILLO", "NÚÑEZ", "IBARRA");
    private static final List<String> NOMBRES = List.of(
            "JOSÉ LUIS", "MARÍA GUADALUPE", "JUAN CARLOS", "ANA MARÍA", "MIGUEL ÁNGEL",
            "ROSA", "FRANCISCO JAVIER", "VERÓNICA", "JESÚS", "MARÍA DEL CARMEN");

    @Override
    public CsvLayout layoutFor(String sourceCode) {
        // Cada publicador entrega lo suyo, y esto imita esa diversidad en vez de esconderla.
        return switch (sourceCode) {
            case "SAT-69B" -> new CsvLayout('|', StandardCharsets.ISO_8859_1,
                    Map.of(CsvLayout.COL_NAME, "CONTRIBUYENTE",
                           CsvLayout.COL_RFC,  "RFC",
                           CsvLayout.COL_PROGRAM, "SUPUESTO"),
                    List.of("dd/MM/yyyy"));
            case "PEP-MX" -> new CsvLayout(',', StandardCharsets.UTF_8,
                    Map.of(CsvLayout.COL_NAME, "NOMBRE COMPLETO",
                           CsvLayout.COL_CURP, "CURP",
                           CsvLayout.COL_DOB,  "FECHA NACIMIENTO",
                           CsvLayout.COL_PROGRAM, "CARGO"),
                    List.of("yyyy-MM-dd", "dd/MM/yyyy"));
            default -> new CsvLayout(',', StandardCharsets.ISO_8859_1,
                    Map.of(CsvLayout.COL_NAME,    "NOMBRE",
                           CsvLayout.COL_CURP,    "CURP",
                           CsvLayout.COL_RFC,     "RFC",
                           CsvLayout.COL_DOB,     "FECHA_NACIMIENTO",
                           CsvLayout.COL_COUNTRY, "PAIS",
                           CsvLayout.COL_PROGRAM, "PROGRAMA"),
                    List.of("yyyy-MM-dd", "dd/MM/yyyy"));
        };
    }

    @Override
    public InputStream download(String sourceCode, WatchlistKind kind, LocalDate publishedOn) {
        CsvLayout layout = layoutFor(sourceCode);
        // Determinista: mismo código + misma fecha ⇒ mismos bytes ⇒ mismo hash.
        Random rnd = new Random((long) sourceCode.hashCode() * 31 + publishedOn.toEpochDay());

        String csv = generate(sourceCode, layout, rnd);
        byte[] bytes = csv.getBytes(layout.charset());

        log.info("[MOCK LISTAS] {} publicada {} — {} bytes en {}",
                sourceCode, publishedOn, bytes.length, layout.charset());
        return new ByteArrayInputStream(bytes);
    }

    private String generate(String sourceCode, CsvLayout layout, Random rnd) {
        char d = layout.delimiter();
        StringBuilder sb = new StringBuilder();

        List<String> headers = orderedHeaders(layout);
        sb.append(String.join(String.valueOf(d), headers)).append('\n');

        int filas = 40 + rnd.nextInt(30);
        for (int i = 0; i < filas; i++) {
            String nombre = nombre(rnd);

            // ── La suciedad, dosificada como en los archivos reales ──────────────────────────
            int dado = rnd.nextInt(100);
            if (dado < 4) {
                // Fila de relleno: el publicador dejó el hueco marcado en vez de omitir la fila.
                sb.append(fila(layout, headers, "N/D", "", "", "", "", "", d)).append('\n');
                continue;
            }
            if (dado < 7) {
                // Línea truncada: se corta a media fila. Pasa con descargas interrumpidas.
                sb.append(esc(nombre, d)).append(d).append("XXXX").append('\n');
                continue;
            }
            if (dado < 9) {
                // Nombre que no deja ningún token: sólo puntuación.
                sb.append(fila(layout, headers, "---", "", "", "", "", "", d)).append('\n');
                continue;
            }

            String curp  = rnd.nextInt(100) < 55 ? curp(nombre, rnd) : "";
            String rfc   = rnd.nextInt(100) < 45 ? rfc(nombre, rnd)  : "";
            // Dos formatos de fecha EN EL MISMO ARCHIVO, que es lo que de verdad llega.
            String fecha = rnd.nextInt(100) < 70
                    ? (rnd.nextBoolean() ? "19%02d-%02d-%02d".formatted(60 + rnd.nextInt(30), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
                                         : "%02d/%02d/19%02d".formatted(1 + rnd.nextInt(28), 1 + rnd.nextInt(12), 60 + rnd.nextInt(30)))
                    : "";
            if (rnd.nextInt(100) < 5) fecha = "00/00/0000";   // fecha imposible: no descarta la fila

            String pais    = rnd.nextInt(100) < 80 ? "MX" : "";
            String programa = programa(sourceCode, rnd);

            sb.append(fila(layout, headers, nombre, curp, rfc, fecha, pais, programa, d)).append('\n');

            // Duplicado exacto: las listas los traen, y no deben tumbar la carga.
            if (rnd.nextInt(100) < 3) {
                sb.append(fila(layout, headers, nombre, curp, rfc, fecha, pais, programa, d)).append('\n');
            }
        }
        return sb.toString();
    }

    private List<String> orderedHeaders(CsvLayout layout) {
        return List.of(CsvLayout.COL_NAME, CsvLayout.COL_CURP, CsvLayout.COL_RFC,
                       CsvLayout.COL_DOB, CsvLayout.COL_COUNTRY, CsvLayout.COL_PROGRAM)
                .stream().map(layout::header).filter(java.util.Objects::nonNull).toList();
    }

    private String fila(CsvLayout layout, List<String> headers, String nombre, String curp,
                        String rfc, String fecha, String pais, String programa, char d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) sb.append(d);
            String h = headers.get(i);
            String v = h.equals(layout.header(CsvLayout.COL_NAME))    ? nombre
                     : h.equals(layout.header(CsvLayout.COL_CURP))    ? curp
                     : h.equals(layout.header(CsvLayout.COL_RFC))     ? rfc
                     : h.equals(layout.header(CsvLayout.COL_DOB))     ? fecha
                     : h.equals(layout.header(CsvLayout.COL_COUNTRY)) ? pais
                     : programa;
            sb.append(esc(v, d));
        }
        return sb.toString();
    }

    /** Entrecomilla si el valor lleva el separador — que pasa con «GARCÍA, JR.» y con los cargos. */
    private String esc(String v, char d) {
        if (v == null) return "";
        return v.indexOf(d) >= 0 ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private String nombre(Random rnd) {
        String n = NOMBRES.get(rnd.nextInt(NOMBRES.size()));
        String p = PATERNOS.get(rnd.nextInt(PATERNOS.size()));
        String m = MATERNOS.get(rnd.nextInt(MATERNOS.size()));
        // Y el orden cambia dentro del mismo archivo: es lo que obliga a guardar el nombre ordenado.
        return rnd.nextBoolean() ? n + " " + p + " " + m : p + " " + m + " " + n;
    }

    private String curp(String nombre, Random rnd) {
        String base = com.fintech.compliance.domain.NormalizedName.of(nombre).value().replace(" ", "");
        String l = (base + "XXXX").substring(0, 4);
        return l + "%02d%02d%02d".formatted(60 + rnd.nextInt(30), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
                 + (rnd.nextBoolean() ? "HDF" : "MDF") + "XXX" + (char) ('A' + rnd.nextInt(26)) + rnd.nextInt(10);
    }

    private String rfc(String nombre, Random rnd) {
        String base = com.fintech.compliance.domain.NormalizedName.of(nombre).value().replace(" ", "");
        return (base + "XXXX").substring(0, 4)
                + "%02d%02d%02d".formatted(60 + rnd.nextInt(30), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
                + (char) ('A' + rnd.nextInt(26)) + rnd.nextInt(10) + rnd.nextInt(10);
    }

    private String programa(String sourceCode, Random rnd) {
        return switch (sourceCode) {
            case "SAT-69B" -> rnd.nextBoolean() ? "DEFINITIVO" : "PRESUNTO";
            case "PEP-MX"  -> List.of("DIPUTADO FEDERAL", "PRESIDENTE MUNICIPAL",
                                      "MAGISTRADO", "DIRECTOR GENERAL, ORGANISMO ESTATAL")
                              .get(rnd.nextInt(4));
            default        -> rnd.nextBoolean() ? "SDN" : "NON-SDN";
        };
    }

    @Override
    public String describeProvider() {
        return "MOCK(listas sintéticas sucias — determinista por código y fecha)";
    }
}
