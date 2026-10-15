package com.fintech.compliance.domain;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * La raíz del CURP: los 10 caracteres que se derivan del nombre y la fecha de nacimiento.
 *
 * <h2>Por qué esto importa</h2>
 *
 * El CURP es el identificador que una persona tiene <b>desde que nace</b>, y su parte determinista
 * no la asigna nadie: <b>se calcula</b> a partir de los apellidos, el nombre y la fecha de
 * nacimiento. Eso permite hacer algo que no se puede con ningún otro dato: <b>reconstruirlo para las
 * entradas de lista que no lo traen</b> — que son la mayoría.
 *
 * <pre>
 *   PEREZ GARCIA JUAN · 1985-03-12   →   PEGJ850312
 *   │    │      │       │                ││││└─┴─┴── fecha AAMMDD
 *   │    │      │       │                │││└─ inicial del nombre
 *   │    │      │       └────────────────┘││
 *   │    │      └── inicial del materno ──┘│
 *   │    └── primera vocal interna ────────┘
 *   └── inicial del paterno
 * </pre>
 *
 * <h2>Lo que gana sobre comparar nombres</h2>
 *
 * La raíz usa <b>iniciales</b>, no nombres completos, así que <b>absorbe las variantes de
 * escritura</b> de forma determinista y sin comparación difusa:
 *
 * <ul>
 *   <li>{@code GONZÁLEZ} y {@code GONZALES} dan la misma raíz — inicial G, primera vocal interna O.</li>
 *   <li>{@code JOSE LUIS} y {@code JOSÉ} también: sólo cuenta la inicial del primer nombre.</li>
 * </ul>
 *
 * Y a la vez es <b>mucho más estricta</b> que un nombre, porque incorpora la fecha de nacimiento
 * exacta: dos homónimos con distinta fecha dan raíces distintas.
 *
 * <h2>Lo que NO hace, y hay que decirlo</h2>
 *
 * <ul>
 *   <li><b>No calcula el CURP completo.</b> Los caracteres 11 a 16 llevan sexo y estado de
 *       nacimiento, que las listas no publican; los dos últimos los asigna RENAPO —diferenciador de
 *       homonimia y dígito verificador— y <b>no son derivables</b>. Por eso se compara sólo la raíz.</li>
 *   <li><b>No aplica las reglas de palabras inconvenientes</b> ni los casos de apellidos compuestos
 *       que RENAPO resuelve con criterios propios. Una raíz derivada aquí es una <b>hipótesis
 *       fuerte</b>, no una identificación — y por eso su coincidencia vale menos que un CURP real y
 *       más que un nombre suelto.</li>
 *   <li><b>No sirve para extranjeros sin CURP</b>, que son la mayoría de una lista de sanciones
 *       internacional. Para ellos el cruce sigue siendo por nombre.</li>
 * </ul>
 *
 * <h2>Y el orden de los apellidos</h2>
 *
 * Las listas no coinciden en el orden —unas traen «APELLIDO APELLIDO NOMBRE», otras al revés, y
 * algunas lo mezclan dentro del mismo archivo—, así que se derivan las raíces de <b>ambas lecturas</b>
 * y se comparan las dos. Son dos cadenas de diez caracteres: sale más barato que adivinar.
 */
public record CurpRoot(String value) {

    private static final String VOCALES = "AEIOU";

    /**
     * Las raíces posibles de un nombre, leído en los dos órdenes.
     *
     * @return conjunto vacío si no hay fecha o el nombre no tiene al menos dos tokens — con un solo
     *         apellido no hay raíz que derivar, y devolver algo a medias produciría coincidencias
     *         falsas que nadie podría explicar
     */
    public static Set<String> candidatesFor(NormalizedName name, LocalDate dateOfBirth) {
        if (name == null || dateOfBirth == null) return Set.of();
        List<String> t = name.tokens();
        if (t.size() < 2) return Set.of();

        String birthDate = "%02d%02d%02d".formatted(
                dateOfBirth.getYear() % 100, dateOfBirth.getMonthValue(), dateOfBirth.getDayOfMonth());

        Set<String> roots = new LinkedHashSet<>();
        int n = t.size();

        // Lectura A — «APELLIDO APELLIDO NOMBRE(S)».
        //
        // El nombre es el token que sigue a los apellidos, NO el último: con «PEREZ GARCIA JOSE
        // LUIS» el último es LUIS y la raíz saldría con la inicial equivocada. Sólo cuenta el
        // primer nombre de pila, que es la regla que hace que «JOSE» y «JOSE LUIS» deriven igual.
        if (n == 2) {
            roots.add(letters(t.get(0), null, t.get(1)) + birthDate);
        } else {
            roots.add(letters(t.get(0), t.get(1), t.get(2)) + birthDate);
        }

        // Lectura B — «NOMBRE(S) APELLIDO APELLIDO»: los apellidos son los dos ÚLTIMOS tokens.
        if (n == 2) {
            roots.add(letters(t.get(1), null, t.get(0)) + birthDate);
        } else {
            roots.add(letters(t.get(n - 2), t.get(n - 1), t.get(0)) + birthDate);
        }
        return roots;
    }

    /** La raíz de un CURP real: sus primeros diez caracteres. */
    public static String of(String curp) {
        if (curp == null || curp.length() < 10) return null;
        return curp.substring(0, 10).toUpperCase();
    }

    private static String letters(String paterno, String materno, String nombre) {
        char p1 = firstLetterOf(paterno);
        char p2 = innerVowelOf(paterno);
        char m1 = firstLetterOf(materno);
        char n1 = firstLetterOf(nombre);
        return "" + p1 + p2 + m1 + n1;
    }

    private static char firstLetterOf(String s) {
        // La X es lo que RENAPO usa cuando falta el dato — por ejemplo, quien no tiene materno.
        return s == null || s.isEmpty() ? 'X' : s.charAt(0);
    }

    /** La primera vocal <b>después</b> de la inicial. «PEREZ» → E; «ÁVILA» ya viene sin acento → V… I. */
    private static char innerVowelOf(String s) {
        if (s == null) return 'X';
        for (int i = 1; i < s.length(); i++) {
            if (VOCALES.indexOf(s.charAt(i)) >= 0) return s.charAt(i);
        }
        return 'X';
    }
}
