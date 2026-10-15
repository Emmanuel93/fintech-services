package com.fintech.compliance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Alguien de una lista que se parece a la persona que se está comprobando.
 *
 * <h2>Lo que descarta a un candidato no es el parecido del nombre: son los discriminadores</h2>
 *
 * El analista no decide mirando un puntaje. Decide mirando <b>qué no coincide</b>: «sí, se llama
 * igual; no, nació en 1985 y el de la lista en 1962, y el de la lista es sirio». Por eso el
 * candidato lleva las dos cosas —por qué coincidió y qué difiere— y no sólo un número.
 *
 * <p>Sin los discriminadores, despejar un candidato obliga a abrir el archivo de origen y compararlo
 * a mano. Con ellos, la cola se despeja leyendo.
 */
public record MatchCandidate(
        UUID entryId,
        WatchlistKind kind,
        String listedName,
        String program,
        String sourceCode,
        UUID listVersionId,
        MatchReason reason,
        /** 0..1 — sólo significa algo para {@link MatchReason#NAME_SIMILAR}. */
        double similarity,
        /** Qué NO coincide. Vacío significa «nada lo descarta», que es lo grave. */
        List<String> discriminators) {

    public MatchCandidate {
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
    }

    /**
     * Calcula qué difiere entre la persona y la entrada de la lista.
     *
     * <p>Un dato que la lista no trae <b>no es un discriminador</b>: que el gobierno no publique la
     * fecha de nacimiento no descarta a nadie. Confundir «no coincide» con «no se sabe» haría que la
     * mitad de los candidatos se despejaran solos, que es la forma más silenciosa de apagar el
     * control.
     */
    public static List<String> discriminate(WatchlistEntry entry,
                                            LocalDate personDateOfBirth,
                                            String personCountry,
                                            String personCurp,
                                            String personRfc) {
        List<String> diffs = new ArrayList<>();

        if (entry.getDateOfBirth() != null && personDateOfBirth != null
                && !entry.getDateOfBirth().equals(personDateOfBirth)) {
            diffs.add("fecha de nacimiento distinta: la persona " + personDateOfBirth
                      + ", la lista " + entry.getDateOfBirth());
        }
        if (entry.getCountry() != null && personCountry != null
                && !entry.getCountry().equalsIgnoreCase(personCountry)) {
            diffs.add("país distinto: la persona " + personCountry + ", la lista " + entry.getCountry());
        }
        if (entry.getCurp() != null && personCurp != null
                && !entry.getCurp().equalsIgnoreCase(personCurp)) {
            diffs.add("CURP distinta");
        }
        if (entry.getRfc() != null && personRfc != null
                && !entry.getRfc().equalsIgnoreCase(personRfc)) {
            diffs.add("RFC distinto");
        }
        return diffs;
    }

    /** Si la lista con la que coincidió detiene a la persona. Ver {@link ScreeningDecisionPolicy}. */
    public boolean blocks() {
        return ScreeningDecisionPolicy.blocks(kind);
    }

    /**
     * Un candidato que <b>nada descarta</b>: coincidió y no hay un solo dato que lo separe.
     *
     * <p>Es el que el analista tiene que mirar primero. Uno con tres discriminadores casi siempre es
     * un homónimo; uno con ninguno puede ser la persona.
     */
    public boolean nothingRulesItOut() {
        return discriminators.isEmpty();
    }
}
