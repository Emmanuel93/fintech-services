package com.fintech.collections.domain;

/**
 * El escalón de tono de la cobranza automática.
 *
 * <p><b>El ciclo dura cinco días y se acaba.</b> No es una cadencia indefinida que sube de
 * intensidad hasta el castigo: son cinco mensajes diarios desde que se cae en mora, y después la
 * gestión la lleva una persona. Un sistema que sigue escribiendo solo durante seis meses no cobra
 * más — acumula quejas y entrena al cliente a ignorar el canal.
 *
 * <p><b>El tono sube, el trato no baja.</b> Empieza asumiendo olvido, que es lo que casi siempre
 * es, y termina ofreciendo hablar. Ninguno amenaza.
 *
 * <p><b>Sobre el historial crediticio</b> (escalón 4): se menciona una sola vez, como consecuencia
 * que se puede evitar y no como amenaza. La diferencia importa y es regulatoria además de ética:
 * informar que el atraso se reporta es decir un hecho; usar el buró como palanca de presión
 * —«te vamos a reportar si no pagas hoy»— es la clase de práctica que CONDUSEF sanciona. El
 * reporte a buró ocurre por el atraso, no por decisión de cobranza, así que no hay nada que
 * ofrecer a cambio de que no ocurra.
 */
public enum DunningStep {

    /** Día 1 — «parece que se te pasó». Sin consecuencias, sin urgencia. */
    RECORDATORIO(1),

    /** Día 2 — el mismo tono, con las formas de pagar a la mano. */
    COMO_PAGAR(2),

    /** Día 3 — se nombra el atraso y lo que se acumula si sigue. */
    ATRASO(3),

    /**
     * Día 4 — única mención al historial crediticio, en positivo: ponerse al corriente lo protege.
     * No se promete no reportar, porque el reporte no depende de cobranza.
     */
    HISTORIAL(4),

    /** Día 5 — cierre del ciclo automático: se ofrece hablar y buscar opciones. */
    OFRECER_AYUDA(5);

    private final int dia;

    DunningStep(int dia) { this.dia = dia; }

    /** Día del ciclo, contado desde el primer día de mora. */
    public int dia() { return dia; }

    /** El escalón que toca en ese día de mora, o vacío si el ciclo automático ya terminó. */
    public static DunningStep forDay(int diasDeMora) {
        for (DunningStep s : values()) {
            if (s.dia == diasDeMora) return s;
        }
        return null;
    }

    /** Cuántos días dura el ciclo completo. */
    public static int cicloDias() { return values().length; }
}
