package com.fintech.documents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que la plantilla consume: parámetros (encabezado, recuadros, resumen) y filas (la tabla).
 *
 * <p>Todo va como texto ya formateado en es-MX. La plantilla no formatea: así un diseñador puede
 * moverla en Jaspersoft Studio sin tocar patrones de moneda ni de fecha, y el PDF sale igual en
 * cualquier JVM, tenga el locale que tenga.
 */
public final class DocumentModel {

    private final Map<String, Object> parameters = new HashMap<>();
    private final List<Map<String, ?>> rows = new ArrayList<>();

    public DocumentModel put(String name, String value) {
        parameters.put(name, value);
        return this;
    }

    /** Una línea etiqueta/valor en una ranura numerada (BOX1, INFO3, SUM5…). */
    public DocumentModel slot(String prefix, int n, String label, String value) {
        parameters.put(prefix + n + "_LABEL", label);
        parameters.put(prefix + n + "_VALUE", value);
        return this;
    }

    public DocumentModel row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        rows.add(row);
        return this;
    }

    /** Copia mutable: Jasper agrega sus propios parámetros al mapa que recibe. */
    public Map<String, Object> parameters() { return new HashMap<>(parameters); }

    public List<Map<String, ?>> rows() { return rows; }

    public Object parameter(String name) { return parameters.get(name); }
}
