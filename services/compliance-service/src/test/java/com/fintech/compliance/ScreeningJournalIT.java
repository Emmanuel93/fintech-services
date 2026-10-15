package com.fintech.compliance;

import com.fintech.compliance.application.port.out.ScreeningCheckRepository;
import com.fintech.compliance.application.service.PersonVerificationService;
import com.fintech.compliance.application.service.RescreenService;
import com.fintech.compliance.domain.NormalizedName;
import com.fintech.compliance.domain.ScreeningStatus;
import com.fintech.compliance.domain.ScreeningSubject;
import com.fintech.compliance.domain.ScreeningTrigger;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La bitácora de consultas, contra Postgres de verdad.
 *
 * <h2>La pregunta que esto existe para contestar</h2>
 *
 * <blockquote>
 * «El 3 de septiembre le dieron un crédito a esta persona que <b>hoy</b> aparece en una lista de
 * sancionados. ¿La consultaron? ¿Cuándo? ¿Qué decía la lista ese día?»
 * </blockquote>
 *
 * <p>Con sólo el veredicto vigente —que se sobrescribe en cada re-cruce— la respuesta es «hoy está
 * señalada», y eso <b>no distingue «no lo sabíamos» de «lo dejamos pasar»</b>. Ésa es exactamente la
 * distinción por la que se responde ante una revisión, y es la que estas pruebas protegen.
 *
 * <p>Va contra base real porque lo que se prueba es que las filas <b>se acumulan</b>: en memoria eso
 * es trivialmente cierto y no prueba nada del esquema, de los índices ni del orden.
 */
@SpringBootTest
class ScreeningJournalIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired PersonVerificationService verifications;
    @Autowired RescreenService rescreen;
    @Autowired ScreeningCheckRepository checks;

    /** Siembra una lista vigente con un sancionado y devuelve su versión. */
    private UUID seedList(String name) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at, activated_at)
                VALUES (?, ?, 'SANCTIONS', ?, 'ACTIVE', 1, 1, 0, now(), now())
                """, versionId, "BIT-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString());

        var n = NormalizedName.of(name);
        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_entries
                    (entry_id, version_id, kind, raw_name, normalized_name, sorted_name,
                     curp, rfc, date_of_birth, country, program, source_line)
                VALUES (?, ?, 'SANCTIONS', ?, ?, ?, NULL, NULL, NULL, 'MX', 'SDN', 2)
                """, UUID.randomUUID(), versionId, name, n.value(), n.sorted());
        return versionId;
    }

    private UUID register(String fullName, UUID prospectId) {
        verifications.evaluateNewPerson(new ScreeningSubject(
                UUID.randomUUID(), fullName, LocalDate.of(1985, 3, 12), null, null, "MX"), prospectId);
        return prospectId;
    }

    // ── Lo que pasa cuando el veredicto cambia ───────────────────────────────────────────────

    @Test
    @DisplayName("un re-cruce que SEÑALA a alguien NO borra la constancia de que salió limpia antes")
    void arescreenDoesNotEraseTheEarlierCleanCheck() {
        // Es el caso entero. Hoy está señalada; el 3 estaba limpia. Las dos cosas son verdad, y el
        // expediente tiene que poder decir las dos — porque de eso depende si hubo negligencia.
        seedList("IVAN PETROV");                      // una lista que no la toca
        UUID prospectId = register("JUAN PEREZ GARCIA", UUID.randomUUID());

        var before = checks.findByProspectId(prospectId, 10);
        assertThat(before).singleElement().satisfies(c -> {
            assertThat(c.getResult()).isEqualTo(ScreeningStatus.CLEARED);
            assertThat(c.getTrigger()).isEqualTo(ScreeningTrigger.ONBOARDING);
        });

        // Ahora se publica una lista donde SÍ aparece, y se re-cruza la población.
        seedList("JUAN PÉREZ GARCÍA");
        rescreen.rescreenAll(null, "MANUAL");

        var history = checks.findByProspectId(prospectId, 10);

        // DOS filas: la de entonces y la de ahora. La primera no se tocó.
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getResult())
                .as("la más reciente la señala")
                .isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(history.get(0).getTrigger()).isEqualTo(ScreeningTrigger.RESCREEN);
        assertThat(history.get(1).getResult())
                .as("y la de antes sigue diciendo que estaba limpia")
                .isEqualTo(ScreeningStatus.CLEARED);
    }

    @Test
    @DisplayName("y el veredicto VIGENTE sí se sobrescribe — son dos cosas distintas y conviven")
    void thecurrentVerdictIsStillOverwritten() {
        // La bitácora no sustituye al veredicto: una contesta «qué sabíamos y cuándo», el otro
        // «puede operar hoy». Confundirlos llevaría a consultar el historial en la compuerta.
        seedList("IVAN PETROV");
        UUID prospectId = register("ROSA MARTINEZ LOPEZ", UUID.randomUUID());

        seedList("ROSA MARTÍNEZ LÓPEZ");
        rescreen.rescreenAll(null, "MANUAL");

        var current = verifications.byProspect(prospectId).orElseThrow();
        assertThat(current.getScreeningStatus()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(checks.countByProspectId(prospectId)).isEqualTo(2);
    }

    // ── Y lo que pasa cuando NO cambia, que es lo que se olvida ──────────────────────────────

    @Test
    @DisplayName("un re-cruce que no cambia nada TAMBIÉN deja constancia: lo que se prueba es que se miró")
    void arescreenThatChangesNothingIsStillJournalled() {
        // Es lo contrario de lo que sugiere el instinto —«guarda sólo lo que cambió»— y es el punto.
        // Guardando sólo los cambios, el expediente de la persona más limpia de la cartera estaría
        // vacío, y «nunca la cruzamos» se vería igual que «la cruzamos doce veces y siempre salió
        // limpia».
        seedList("IVAN PETROV");
        UUID prospectId = register("MIGUEL TORRES RUIZ", UUID.randomUUID());

        rescreen.rescreenAll(null, "MANUAL");
        rescreen.rescreenAll(null, "MANUAL");

        assertThat(checks.countByProspectId(prospectId))
                .as("el alta y los dos re-cruces, aunque el veredicto no se moviera")
                .isEqualTo(3);
        assertThat(checks.findByProspectId(prospectId, 10))
                .allSatisfy(c -> assertThat(c.getResult()).isEqualTo(ScreeningStatus.CLEARED));
    }

    // ── La reproducibilidad, que es lo que hace útil la constancia ───────────────────────────

    @Test
    @DisplayName("cada consulta guarda contra qué versiones corrió, así que se puede volver a correr")
    void eachCheckNamesTheVersionsItRanAgainst() {
        UUID versionId = seedList("IVAN PETROV");
        UUID prospectId = register("ANA GOMEZ DIAZ", UUID.randomUUID());

        assertThat(checks.findByProspectId(prospectId, 10)).singleElement().satisfies(c -> {
            assertThat(c.hadListsToCheckAgainst()).isTrue();
            assertThat(c.getListVersions()).contains(versionId.toString());
        });
    }

    @Test
    @DisplayName("el historial llega ordenado de lo más reciente a lo más viejo")
    void thehistoryComesNewestFirst() {
        // Es el orden en que lo mira quien audita, y el índice está puesto para eso. Si llegara al
        // revés habría que ordenarlo en memoria sobre una tabla que crece con cada re-cruce.
        seedList("IVAN PETROV");
        UUID prospectId = register("LUIS NAVARRO SOTO", UUID.randomUUID());
        rescreen.rescreenAll(null, "MANUAL");

        var history = checks.findByProspectId(prospectId, 10);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getCheckedAt())
                .isAfterOrEqualTo(history.get(1).getCheckedAt());
    }
}
