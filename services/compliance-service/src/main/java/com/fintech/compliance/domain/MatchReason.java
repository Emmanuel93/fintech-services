package com.fintech.compliance.domain;

/**
 * Por qué una persona coincidió con una entrada de lista.
 *
 * <h2>No es un puntaje: es un tipo de evidencia</h2>
 *
 * Un solo número —«0.87 de parecido»— no le dice nada a quien tiene que adjudicar. Necesita saber
 * <b>qué</b> coincidió, porque coincidir en CURP y coincidir en apellidos son hechos de fuerza
 * radicalmente distinta y se descartan de maneras distintas.
 *
 * <p>Están ordenados de más fuerte a más débil, y el orden importa: la evidencia más fuerte que se
 * encontró es la que manda, aunque haya otras más débiles.
 */
public enum MatchReason {

    /**
     * CURP o RFC idénticos. Es una <b>identificación</b>, no un parecido: coincide la persona.
     *
     * <p>Casi nunca ocurre, y por eso es tan valiosa cuando ocurre: las listas de gobierno rara vez
     * traen documentos mexicanos.
     */
    DOCUMENT_IDENTICAL(100, true),

    /**
     * Nombre normalizado y fecha de nacimiento, las dos.
     *
     * <p>Dos coincidencias independientes a la vez. Que dos personas compartan nombre es común; que
     * compartan nombre <b>y</b> día de nacimiento, no.
     */
    NAME_AND_DATE_OF_BIRTH(90, true),

    /**
     * La <b>raíz del CURP</b> coincide: las iniciales de los apellidos y del nombre, la primera
     * vocal interna del paterno, y la fecha de nacimiento exacta.
     *
     * <p>Existe porque la mayoría de las entradas de lista <b>no traen CURP</b> —hay listas que ni
     * siquiera tienen la columna— y sin esto quedan fuera del único cruce que identifica de verdad.
     * La raíz se deriva del nombre y la fecha, que sí traen, y se compara contra la raíz del CURP
     * real de la persona. Ver {@link CurpRoot}.
     *
     * <p><b>Por qué vale menos que un documento y más que un nombre.</b> Menos, porque es una raíz
     * <b>derivada</b>: los caracteres que RENAPO asigna no son calculables, así que dos personas
     * distintas pueden compartirla. Más, porque incorpora la fecha de nacimiento exacta y absorbe
     * las variantes de escritura —{@code GONZÁLEZ} y {@code GONZALES} dan la misma raíz— sin
     * depender de ningún umbral de parecido.
     *
     * <p>Se marca como identificante por la misma razón que {@link #NAME_AND_DATE_OF_BIRTH}: lleva
     * la fecha dentro, y eso lo saca del montón de homónimos que se despeja en masa.
     */
    CURP_ROOT(75, true),

    /**
     * Los mismos tokens del nombre, en cualquier orden.
     *
     * <p><b>Esto es una sospecha, no una identificación.</b> «JUAN PEREZ GARCIA» coincide con
     * decenas de personas reales, ninguna sancionada — y ésta será la mayoría de los casos.
     */
    NAME_EXACT(60, false),

    /**
     * Nombre parecido por encima del umbral: «GONZALES» por «GONZÁLEZ», un apellido de menos.
     *
     * <p>La más débil y la que más ruido produce. Existe porque las listas traen transliteraciones y
     * errores de captura, y sin ella se escapa quien está escrito con una letra distinta.
     */
    NAME_SIMILAR(30, false);

    private final int strength;
    private final boolean identifying;

    MatchReason(int strength, boolean identifying) {
        this.strength    = strength;
        this.identifying = identifying;
    }

    public int strength() { return strength; }

    /**
     * Si la evidencia <b>identifica</b> a la persona o sólo la hace sospechosa.
     *
     * <p>La distinción existe para la cola del analista: lo identificante se revisa primero, y lo
     * meramente parecido es lo que se despeja en masa mirando discriminadores.
     */
    public boolean isIdentifying() { return identifying; }
}
