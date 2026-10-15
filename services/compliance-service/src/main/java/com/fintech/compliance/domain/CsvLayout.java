package com.fintech.compliance.domain;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Cómo se lee el archivo de una lista concreta.
 *
 * <h2>Por qué el layout es un dato y no código</h2>
 *
 * Cada publicador entrega lo suyo: unos separan por coma, otros por punto y coma o por pipe; unos
 * mandan UTF-8 y otros Latin-1; y los nombres de columna no se parecen entre listas ni entre años.
 * Escribir un parser por lista significa un despliegue cada vez que una dependencia de gobierno
 * cambia una cabecera — y cambian sin avisar.
 *
 * @param delimiter   el separador real del archivo. No se adivina: se declara
 * @param charset     <b>Latin-1 es lo común</b> en publicaciones oficiales mexicanas. Leer Latin-1
 *                    como UTF-8 no revienta: produce «PEÃ‘A» en vez de «PEÑA», que normaliza a
 *                    «PEA» y deja de coincidir con nadie. Es un fallo silencioso, y por eso el
 *                    encoding se declara en vez de detectarse
 * @param columns     de nombre lógico (`name`, `curp`, `rfc`, `dateOfBirth`, `country`, `program`)
 *                    a la cabecera del archivo. Sólo `name` es obligatorio
 * @param datePatterns los formatos de fecha admitidos, <b>en orden</b>. Son varios porque el mismo
 *                    archivo suele traer dos
 */
public record CsvLayout(
        char delimiter,
        Charset charset,
        Map<String, String> columns,
        java.util.List<String> datePatterns) {

    public static final String COL_NAME     = "name";
    public static final String COL_CURP     = "curp";
    public static final String COL_RFC      = "rfc";
    public static final String COL_DOB      = "dateOfBirth";
    public static final String COL_COUNTRY  = "country";
    public static final String COL_PROGRAM  = "program";

    public CsvLayout {
        if (columns == null || !columns.containsKey(COL_NAME)) {
            throw new IllegalArgumentException(
                    "Un layout sin columna de nombre no puede cargar nada: el nombre es lo único "
                    + "que todas las listas traen");
        }
        columns = Map.copyOf(columns);
        datePatterns = datePatterns == null || datePatterns.isEmpty()
                ? java.util.List.of("yyyy-MM-dd", "dd/MM/yyyy")
                : java.util.List.copyOf(datePatterns);
    }

    public String header(String logical) { return columns.get(logical); }
}
