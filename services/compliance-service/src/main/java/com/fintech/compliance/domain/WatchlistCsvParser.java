package com.fintech.compliance.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Lee el CSV de una lista según su {@link CsvLayout}.
 *
 * <h2>La regla que lo gobierna: una fila mala no tumba la carga; un archivo sin filas buenas sí</h2>
 *
 * Los archivos de gobierno vienen sucios —campos vacíos, fechas en dos formatos, líneas truncadas,
 * comillas sin cerrar, duplicados—. Abortar la carga en el primer defecto significa no tener lista,
 * y no tener lista significa que todo el mundo sale limpio. Así que cada fila mala se rechaza con su
 * número de línea y su motivo, y la carga sigue.
 *
 * <p>Lo que no se tolera es un archivo del que no salió nada: eso no es una lista vacía, es un
 * layout que cambió o una descarga truncada. Esa distinción la hace {@link WatchlistVersion}.
 *
 * <h2>Por qué no se usa una librería de CSV</h2>
 *
 * Porque las librerías buenas fallan rápido —lanzan al primer registro malformado— y aquí eso es
 * exactamente lo que no se quiere. Y porque el archivo se lee una vez, en streaming, sin cargarlo en
 * memoria: una lista de sancionados son cientos de miles de filas.
 */
public final class WatchlistCsvParser {

    /** Una fila que no se pudo usar, con lo necesario para arreglarla sin adivinar. */
    public record Rejection(int line, String reason, String raw) {}

    private final CsvLayout layout;

    public WatchlistCsvParser(CsvLayout layout) {
        this.layout = layout;
    }

    /**
     * Lee el archivo y entrega cada fila, buena o mala, al consumidor que corresponda.
     *
     * <p>Se entrega en streaming y no se devuelve una lista: el llamador va contando y persistiendo
     * por lotes. Devolver `List<WatchlistEntry>` obligaría a tener el archivo entero en memoria, que
     * es justo lo que no cabe.
     *
     * @return el número de líneas de datos leídas (sin contar la cabecera)
     */
    public int parse(InputStream in, UUID versionId, WatchlistKind kind,
                     BiConsumer<WatchlistEntry, Integer> onEntry,
                     java.util.function.Consumer<Rejection> onRejection) throws IOException {

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, layout.charset()))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return 0;

            // El BOM de UTF-8 se cuela en la primera cabecera y la vuelve irreconocible: la columna
            // "name" pasa a llamarse "﻿name" y no la encuentra nadie. Cuesta una línea quitarlo
            // y horas descubrirlo.
            if (!headerLine.isEmpty() && headerLine.charAt(0) == '﻿') {
                headerLine = headerLine.substring(1);
            }

            Map<String, Integer> index = headerIndex(headerLine);
            Integer nameAt = index.get(norm(layout.header(CsvLayout.COL_NAME)));
            if (nameAt == null) {
                throw new IllegalStateException(
                        "El archivo no trae la columna de nombre «" + layout.header(CsvLayout.COL_NAME)
                        + "». Cabeceras encontradas: " + index.keySet());
            }

            int lineNo = 1;
            String line;
            int dataRows = 0;

            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;      // línea en blanco no es fila mala, es nada
                dataRows++;

                List<String> cells = split(line);

                String rawName = at(cells, nameAt);
                if (rawName == null || rawName.isBlank()) {
                    onRejection.accept(new Rejection(lineNo, "sin nombre", recorta(line)));
                    continue;
                }

                NormalizedName normalized = NormalizedName.of(rawName);
                if (normalized.isEmpty()) {
                    // Pasa de verdad: nombres que son sólo puntuación, o «---», o «N/D».
                    onRejection.accept(new Rejection(lineNo,
                            "el nombre no deja ningún token utilizable: «" + rawName + "»", recorta(line)));
                    continue;
                }

                LocalDate dob;
                String dobRaw = cell(cells, index, CsvLayout.COL_DOB);
                try {
                    dob = parseDate(dobRaw);
                } catch (RuntimeException e) {
                    // Una fecha ilegible NO descarta la fila: el nombre sigue sirviendo para cruzar,
                    // y descartar por eso sería perder a alguien de la lista por un formato.
                    dob = null;
                }

                WatchlistEntry entry = WatchlistEntry.of(
                        versionId, kind, rawName, normalized,
                        cell(cells, index, CsvLayout.COL_CURP),
                        cell(cells, index, CsvLayout.COL_RFC),
                        dob,
                        cell(cells, index, CsvLayout.COL_COUNTRY),
                        cell(cells, index, CsvLayout.COL_PROGRAM),
                        lineNo);

                onEntry.accept(entry, lineNo);
            }
            return dataRows;
        }
    }

    // ── Interno ──────────────────────────────────────────────────────────────────────────────

    private Map<String, Integer> headerIndex(String headerLine) {
        Map<String, Integer> index = new HashMap<>();
        List<String> heads = split(headerLine);
        for (int i = 0; i < heads.size(); i++) {
            index.put(norm(heads.get(i)), i);
        }
        return index;
    }

    /** Las cabeceras vienen con espacios, acentos y mayúsculas inconsistentes entre publicaciones. */
    private static String norm(String s) {
        return s == null ? "" : NormalizedName.of(s).value();
    }

    private String cell(List<String> cells, Map<String, Integer> index, String logical) {
        String header = layout.header(logical);
        if (header == null) return null;
        Integer at = index.get(norm(header));
        return at == null ? null : at(cells, at);
    }

    private static String at(List<String> cells, int i) {
        // Las filas truncadas son comunes: se pide una columna que esa línea no trae.
        return i < cells.size() ? cells.get(i) : null;
    }

    /**
     * Separa respetando comillas. Una comilla sin cerrar no rompe: el resto de la línea va al último
     * campo, que es lo que hace un lector tolerante y lo que permite quedarse con el nombre.
     */
    private List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean entreComillas = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (entreComillas && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    actual.append('"');   // comilla escapada
                    i++;
                } else {
                    entreComillas = !entreComillas;
                }
            } else if (c == layout.delimiter() && !entreComillas) {
                out.add(actual.toString().trim());
                actual.setLength(0);
            } else {
                actual.append(c);
            }
        }
        out.add(actual.toString().trim());
        return out;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        for (String pattern : layout.datePatterns()) {
            try {
                return LocalDate.parse(v, DateTimeFormatter.ofPattern(pattern));
            } catch (RuntimeException ignored) {
                // se prueba el siguiente formato declarado
            }
        }
        throw new IllegalArgumentException("fecha no reconocida: " + raw);
    }

    private static String recorta(String line) {
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }
}
