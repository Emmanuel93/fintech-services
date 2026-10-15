package com.fintech.compliance.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Un nombre llevado a la forma en la que se puede comparar.
 *
 * <h2>Por qué se normaliza al CARGAR y no al consultar</h2>
 *
 * Si cada consulta normaliza por su cuenta, dos servicios que llamen con el mismo nombre pueden
 * obtener resultados distintos porque uno quita acentos y el otro no. Peor: el día que se cambie la
 * normalización, los nombres ya guardados dejan de corresponder con los que se buscan, y el índice
 * deja de servir sin que nada falle. Guardar la forma normalizada convierte eso en una migración
 * explícita —hay que recargar las listas— en vez de una degradación silenciosa.
 *
 * <h2>Qué hace, y por qué cada cosa</h2>
 *
 * <ol>
 *   <li><b>Mayúsculas y sin acentos.</b> Las listas de gobierno mezclan «PEÑA» y «PENA», «JOSÉ» y
 *       «JOSE», a veces en el mismo archivo. Tratarlos como distintos es no encontrar a quien se
 *       busca.</li>
 *   <li><b>La Ñ se conserva como N</b> y no se pierde: «MUÑOZ» → «MUNOZ», que es como la escriben
 *       los sistemas que no manejan la ñ — y son muchos.</li>
 *   <li><b>Se quita la puntuación</b> pero no se pegan las palabras: «O'BRIEN» → «O BRIEN», no
 *       «OBRIEN». Unirlas inventa un token que nadie escribe.</li>
 *   <li><b>Las partículas se conservan.</b> «DE LA CRUZ» no se reduce a «CRUZ»: en México la
 *       partícula es parte del apellido y quitarla multiplica los falsos positivos.</li>
 *   <li><b>Los espacios se colapsan</b> y los tokens se guardan además <b>ordenados</b>.</li>
 * </ol>
 *
 * <h2>Y por qué se guarda también el nombre ORDENADO</h2>
 *
 * Las listas no coinciden en el orden: unas traen «APELLIDO APELLIDO NOMBRE», otras «NOMBRE
 * APELLIDO APELLIDO», y algunas lo mezclan dentro del mismo archivo. Comparar la cadena tal cual
 * falla con la misma persona escrita al revés. El campo ordenado —los mismos tokens, alfabéticos—
 * hace que «PEREZ GARCIA JUAN» y «JUAN PEREZ GARCIA» sean idénticos.
 *
 * <p>No sustituye a la comparación difusa: la resuelve para el caso más común, que es éste.
 */
public record NormalizedName(String value, String sorted, List<String> tokens) {

    private static final Set<String> RUIDO = new LinkedHashSet<>(Arrays.asList(
            // Títulos y tratamientos que unas listas traen y otras no. Ninguno identifica a nadie.
            "SR", "SRA", "SRTA", "DR", "DRA", "LIC", "ING", "MTRO", "MTRA", "C", "CC"));

    public static NormalizedName of(String raw) {
        if (raw == null || raw.isBlank()) {
            return new NormalizedName("", "", List.of());
        }

        String sinAcentos = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");                 // quita las marcas diacríticas

        String limpio = sinAcentos.toUpperCase()
                .replaceAll("[^A-Z0-9Ñ ]", " ")             // la puntuación separa, no pega
                .replaceAll("\\s+", " ")
                .trim();

        List<String> tokens = Arrays.stream(limpio.split(" "))
                .filter(t -> !t.isEmpty())
                .filter(t -> !RUIDO.contains(t))
                .toList();

        String value  = String.join(" ", tokens);
        String sorted = tokens.stream().sorted().reduce((a, b) -> a + " " + b).orElse("");

        return new NormalizedName(value, sorted, tokens);
    }

    public boolean isEmpty() { return tokens.isEmpty(); }

    /**
     * Mismo nombre escrito en otro orden.
     *
     * <p>No es «parecido»: es exactamente los mismos tokens. Es la coincidencia más fuerte que se
     * puede afirmar sin entrar en comparación difusa, y la que resuelve el desorden entre listas.
     */
    public boolean sameTokensAs(NormalizedName other) {
        return other != null && !isEmpty() && sorted.equals(other.sorted());
    }
}
