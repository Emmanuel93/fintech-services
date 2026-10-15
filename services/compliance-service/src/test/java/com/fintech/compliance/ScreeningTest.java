package com.fintech.compliance;

import com.fintech.compliance.application.port.out.WatchlistSearchPort;
import com.fintech.compliance.application.service.ScreeningService;
import com.fintech.compliance.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cruce contra listas, y por qué su resultado no es un booleano.
 *
 * <p>Lo que estas pruebas protegen es la parte que decide si el control sirve o estorba: que la
 * homonimia —que es lo común, no la excepción— se pueda despejar mirando <b>discriminadores</b> en
 * vez de re-investigando a cada persona.
 */
class ScreeningTest {

    private static final UUID VERSION = UUID.randomUUID();

    // ── Un repositorio de listas en memoria ──────────────────────────────────────────────────

    private static final class Lists implements WatchlistSearchPort {
        final List<WatchlistEntry> rows = new ArrayList<>();
        boolean hasActiveLists = true;

        @Override public Set<UUID> activeVersionIds() { return hasActiveLists ? Set.of(VERSION) : Set.of(); }

        @Override public List<WatchlistEntry> findByDocument(String curp, String rfc, Set<UUID> v) {
            return rows.stream().filter(e ->
                    (curp != null && curp.equalsIgnoreCase(e.getCurp()))
                 || (rfc  != null && rfc.equalsIgnoreCase(e.getRfc()))).toList();
        }
        @Override public List<WatchlistEntry> findByCurpRoot(Set<String> roots, Set<UUID> v) {
            return rows.stream().filter(e -> !java.util.Collections.disjoint(e.curpRoots(), roots)).toList();
        }
        @Override public List<WatchlistEntry> findBySortedName(String sorted, Set<UUID> v) {
            return rows.stream().filter(e -> sorted.equals(e.getSortedName())).toList();
        }
        @Override public List<ScoredEntry> findSimilarNames(String name, double min, Set<UUID> v) {
            // Parecido de juguete: comparte el primer token y no es exacto.
            String primero = name.split(" ")[0];
            return rows.stream()
                    .filter(e -> e.getNormalizedName().startsWith(primero))
                    .filter(e -> !e.getNormalizedName().equals(name))
                    .map(e -> new ScoredEntry(e, 0.88)).toList();
        }

        Lists with(WatchlistKind kind, String name, String curp, LocalDate dob, String country, String program) {
            rows.add(WatchlistEntry.of(VERSION, kind, name, NormalizedName.of(name),
                    curp, null, dob, country, program, rows.size() + 2));
            return this;
        }
    }

    private static ScreeningService serviceWith(Lists lists) {
        return new ScreeningService(lists, 0.85);
    }

    private static ScreeningSubject person(String name, LocalDate dob, String curp, String country) {
        return new ScreeningSubject(UUID.randomUUID(), name, dob, curp, null, country);
    }

    // ── Lo básico ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin coincidencias queda CLEARED")
    void noMatchIsCleared() {
        var out = serviceWith(new Lists().with(WatchlistKind.SANCTIONS, "IVÁN PETROV", null, null, "RU", "SDN"))
                .screen(person("JUAN PÉREZ GARCÍA", LocalDate.of(1985, 3, 12), "PEGJ850312HDFXXX01", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
        assertThat(out.status().allowsToProceed()).isTrue();
    }

    @Test
    @DisplayName("coincidir en documento es una IDENTIFICACIÓN, no un parecido")
    void aDocumentMatchIsAnIdentification() {
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "J PEREZ", "PEGJ850312HDFXXX01", null, null, "SDN");
        var out = serviceWith(lists)
                .screen(person("JUAN PÉREZ GARCÍA", LocalDate.of(1985, 3, 12), "PEGJ850312HDFXXX01", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.candidates()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(MatchReason.DOCUMENT_IDENTICAL);
            assertThat(c.reason().isIdentifying()).isTrue();
        });
    }

    @Test
    @DisplayName("el nombre coincide aunque venga en otro orden")
    void nameOrderDoesNotMatter() {
        // Las listas mezclan «APELLIDO APELLIDO NOMBRE» y «NOMBRE APELLIDO APELLIDO».
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "PÉREZ GARCÍA JUAN", null, null, "MX", "SDN");
        var out = serviceWith(lists).screen(person("Juan Perez Garcia", null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.candidates().get(0).reason()).isEqualTo(MatchReason.NAME_EXACT);
    }

    // ── La homonimia, que es el problema real ────────────────────────────────────────────────

    @Test
    @DisplayName("un homónimo sigue bloqueando, PERO trae los discriminadores que lo despejan")
    void aNamesakeBlocksButCarriesWhatClearsIt() {
        // Es el caso común: mismo nombre, otra persona. El control no puede decidir solo —por eso
        // bloquea— pero sí puede darle al analista lo que necesita para despejarlo leyendo.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA",
                null, LocalDate.of(1962, 1, 1), "SY", "SDN");

        var out = serviceWith(lists)
                .screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);

        MatchCandidate c = out.candidates().get(0);
        assertThat(c.discriminators()).hasSize(2);
        assertThat(c.discriminators()).anySatisfy(d -> assertThat(d).contains("fecha de nacimiento distinta"));
        assertThat(c.discriminators()).anySatisfy(d -> assertThat(d).contains("país distinto"));
        assertThat(c.nothingRulesItOut()).isFalse();
    }

    @Test
    @DisplayName("un dato que la lista NO trae no descarta a nadie")
    void amissingFieldIsNotADiscriminator() {
        // Que el gobierno no publique la fecha de nacimiento no separa a dos personas. Confundir
        // «no coincide» con «no se sabe» despejaría solo a la mitad de los candidatos.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA", null, null, null, "SDN");

        var out = serviceWith(lists)
                .screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, "MX"));

        assertThat(out.candidates().get(0).discriminators()).isEmpty();
        assertThat(out.candidates().get(0).nothingRulesItOut())
                .as("nada lo descarta: es el que el analista mira primero")
                .isTrue();
        assertThat(out.unexplained()).hasSize(1);
    }

    @Test
    @DisplayName("mismo nombre Y misma fecha es mucho más fuerte que sólo el nombre")
    void nameAndDateIsStronger() {
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA",
                null, LocalDate.of(1985, 3, 12), "MX", "SDN");

        var out = serviceWith(lists)
                .screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, "MX"));

        assertThat(out.candidates().get(0).reason()).isEqualTo(MatchReason.NAME_AND_DATE_OF_BIRTH);
        assertThat(out.candidates().get(0).reason().isIdentifying()).isTrue();
    }

    @Test
    @DisplayName("la misma entrada por dos caminos se reporta UNA vez, con la evidencia más fuerte")
    void oneEntryIsReportedOnceWithItsStrongestEvidence() {
        // Sin deduplicar, el analista revisa lo mismo dos veces; con la evidencia débil, pierde
        // fuerza lo que de verdad se encontró.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA",
                "PEGJ850312HDFXXX01", LocalDate.of(1985, 3, 12), "MX", "SDN");

        var out = serviceWith(lists)
                .screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), "PEGJ850312HDFXXX01", "MX"));

        assertThat(out.candidates()).hasSize(1);
        assertThat(out.candidates().get(0).reason()).isEqualTo(MatchReason.DOCUMENT_IDENTICAL);
    }

    @Test
    @DisplayName("los candidatos salen ordenados: primero la evidencia más fuerte")
    void candidatesComeSorted() {
        var lists = new Lists()
                .with(WatchlistKind.SANCTIONS, "JUANITO PÉREZ", null, null, "MX", "SDN")          // parecido
                .with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA", null,
                        LocalDate.of(1985, 3, 12), "MX", "SDN");                                  // nombre+fecha

        var out = serviceWith(lists)
                .screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, "MX"));

        assertThat(out.candidates().get(0).reason()).isEqualTo(MatchReason.NAME_AND_DATE_OF_BIRTH);
    }

    // ── Qué bloquea cada lista ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un PEP BLOQUEA — decisión del negocio, no la práctica habitual")
    void aPepBlocks() {
        // La práctica habitual de cumplimiento es diligencia reforzada y no bloqueo, porque bloquear
        // equivale a negar servicio a un funcionario público por serlo. Se planteó y el negocio
        // decidió bloquear. La prueba fija esa decisión para que cambiarla sea deliberado.
        var lists = new Lists().with(WatchlistKind.PEP, "JUAN PÉREZ GARCÍA",
                null, null, "MX", "DIPUTADO FEDERAL");

        var out = serviceWith(lists).screen(person("JUAN PEREZ GARCIA", null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.status().allowsToProceed()).isFalse();
    }

    @Test
    @DisplayName("la lista fiscal se REGISTRA pero no bloquea — decisión pendiente")
    void theTaxListDoesNotBlockYet() {
        // Es un hecho fiscal, no de identidad, y la decisión de qué hacer con él quedó abierta.
        // Mientras tanto se informa sin bloquear: bloquear sería aplicar un criterio que nadie aprobó.
        var lists = new Lists().with(WatchlistKind.TAX_BLACKLIST, "JUAN PÉREZ GARCÍA",
                null, null, "MX", "DEFINITIVO");

        var out = serviceWith(lists).screen(person("JUAN PEREZ GARCIA", null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
        assertThat(out.candidates())
                .as("no bloquea, pero queda registrado y visible")
                .hasSize(1);
    }

    // ── La polaridad, otra vez ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin ninguna lista vigente el veredicto es PENDING, nunca CLEARED")
    void withoutListsTheVerdictIsPendingNotClean() {
        // Decir «limpio» sin listas es afirmar que se comprobó algo que no se comprobó. Es el mismo
        // fallo-abierto que tenía el control de `channels`, y aquí no se repite.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "JUAN PÉREZ GARCÍA", null, null, "MX", "SDN");
        lists.hasActiveLists = false;

        var out = serviceWith(lists).screen(person("JUAN PEREZ GARCIA", null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.PENDING);
        assertThat(out.status().allowsToProceed()).isFalse();
    }

    @Test
    @DisplayName("el veredicto guarda contra qué versiones de lista se corrió")
    void theVerdictRecordsTheListVersions() {
        // Las listas cambian: alguien despejado hace seis meses puede estar hoy. Sin la versión, el
        // veredicto no se puede auditar ni re-correr.
        var out = serviceWith(new Lists()).screen(person("JUAN PEREZ GARCIA", null, null, "MX"));

        assertThat(out.listVersions()).containsExactly(VERSION);
    }
    // ── El CURP derivado: el cruce por documento donde no hay documento ──────────────────────

    @Test
    @DisplayName("la raíz del CURP encuentra a quien el nombre escrito distinto dejaría escapar")
    void thecurpRootCatchesASpellingVariant() {
        // La lista publica «GONZALES» —con ese—, la persona se llama «GONZÁLEZ». El nombre
        // normalizado NO coincide, así que el cruce por nombre exacto no la encuentra; el de
        // parecido depende de un umbral que alguien tiene que calibrar bien.
        //
        // La raíz sí: usa la inicial y la primera vocal interna, que no cambian con la variante, y
        // le suma la fecha de nacimiento exacta. Es determinista y no tiene umbral que ajustar.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA",
                null, LocalDate.of(1985, 3, 12), "MX", "SDN");

        var out = serviceWith(lists).screen(
                person("GONZÁLEZ LÓPEZ MARÍA", LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.candidates()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(MatchReason.CURP_ROOT);
            assertThat(c.reason().isIdentifying()).isTrue();
        });
    }

    @Test
    @DisplayName("una entrada con CURP no se cruza por raíz: el documento ya la identificó")
    void anentryWithACurpIsNotMatchedByRoot() {
        // Derivarle una raíz a algo que ya trae el documento completo sólo añadiría evidencia más
        // débil sobre un hecho ya resuelto — y ensuciaría la cola con parecidos donde hay certeza.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA",
                "GOLM850312MDFNPR99", LocalDate.of(1985, 3, 12), "MX", "SDN");

        assertThat(lists.rows.get(0).curpRoots()).isEmpty();

        // Y con CURP distinto al de la persona, no coincide por ningún camino.
        var out = serviceWith(lists).screen(
                person("GONZÁLEZ LÓPEZ MARÍA", LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
    }

    @Test
    @DisplayName("sin fecha en la lista no hay raíz que derivar, y esa entrada queda fuera del cruce")
    void without_aDateTheEntryStaysOutOfTheRootSearch() {
        // Es el límite honesto de la técnica y hay que dejarlo escrito: una lista que no publica ni
        // CURP ni fecha de nacimiento no se alcanza por documento de ninguna forma. Sólo por nombre.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA",
                null, null, "MX", "SDN");

        var out = serviceWith(lists).screen(
                person("GONZÁLEZ LÓPEZ MARÍA", LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
    }

    @Test
    @DisplayName("cuando el nombre TAMBIÉN coincide, manda la evidencia más fuerte, no la raíz")
    void thestrongerEvidenceWins() {
        // La misma entrada cae por los dos caminos. Reportarla por la raíz le quitaría fuerza a lo
        // que de verdad se encontró: el nombre completo y la fecha, que es más información.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "GONZALEZ LOPEZ MARIA",
                null, LocalDate.of(1985, 3, 12), "MX", "SDN");

        var out = serviceWith(lists).screen(
                person("GONZÁLEZ LÓPEZ MARÍA", LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.candidates()).singleElement().satisfies(c ->
                assertThat(c.reason()).isEqualTo(MatchReason.NAME_AND_DATE_OF_BIRTH));
    }

    @Test
    @DisplayName("la fecha distinta separa a los homónimos: mismo nombre, raíz distinta")
    void adifferentDateOfBirthSeparatesNamesakes() {
        // Es lo que la raíz aporta sobre el nombre suelto. «MARÍA GONZÁLEZ LÓPEZ» hay muchas; con
        // una fecha de nacimiento concreta, muy pocas.
        var lists = new Lists().with(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA",
                null, LocalDate.of(1962, 1, 1), "MX", "SDN");

        var out = serviceWith(lists).screen(
                person("GONZÁLEZ LÓPEZ MARÍA", LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
    }

}
