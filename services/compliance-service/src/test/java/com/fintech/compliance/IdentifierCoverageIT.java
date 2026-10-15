package com.fintech.compliance;

import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuánto de cada lista se puede encontrar cruzando por CURP.
 *
 * <p>Cruzar por documento es lo ideal: es una <b>identificación</b> y no produce homónimos. Pero sólo
 * sirve sobre las entradas que traen el documento, y eso lo decide el publicador.
 *
 * <p>Lo que estas pruebas fijan es que ese número se <b>mida</b>. Sin él, «siempre cruzamos por
 * CURP» suena a control estricto y puede ser un control que nunca dispara: si el 3 % de la lista
 * trae CURP, se comprueba contra el 3 % y el 97 % sale limpio sin que nada falle.
 */
@SpringBootTest
class IdentifierCoverageIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired WatchlistLoadService loads;

    private record Cobertura(int aceptadas, Integer conCurp, Integer conRfc, Integer conRaiz) {
        BigDecimal curpPct()  { return pct(conCurp); }

        /** Lo publicado más lo derivado: la fracción que el cruce por CURP alcanza de verdad. */
        BigDecimal alcancePct() {
            return pct((conCurp == null ? 0 : conCurp) + (conRaiz == null ? 0 : conRaiz));
        }

        private BigDecimal pct(Integer n) {
            return aceptadas == 0 || n == null ? BigDecimal.ZERO
                    : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(aceptadas), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    private Cobertura coverageOf(String sourceCode) {
        return jdbcTemplate.queryForObject("""
                SELECT accepted_rows, entries_with_curp, entries_with_rfc, entries_with_curp_root
                  FROM compliance.watchlist_versions
                 WHERE source_code = ? AND status = 'ACTIVE'
                """, (rs, i) -> new Cobertura(rs.getInt(1), (Integer) rs.getObject(2),
                        (Integer) rs.getObject(3), (Integer) rs.getObject(4)),
                sourceCode);
    }

    @Test
    @DisplayName("una lista SIN columna de CURP tiene cobertura CERO — cruzarla por CURP no encuentra nada")
    void alistWithoutACurpColumnHasZeroCoverage() {
        // `SAT-69B` ni siquiera declara columna de CURP: trae RFC. Es el caso que demuestra que
        // «siempre por CURP» no puede ser la única vía — sobre esta lista sería un control apagado.
        loads.load("SAT-69B", WatchlistKind.TAX_BLACKLIST, LocalDate.of(2026, 9, 1));

        Cobertura c = coverageOf("SAT-69B");

        assertThat(c.conCurp()).isZero();
        assertThat(c.curpPct()).isEqualByComparingTo("0.00");
        assertThat(c.conRfc())
                .as("pero sí trae RFC: el documento correcto para esta lista es otro")
                .isPositive();

        // Y la raíz derivada TAMPOCO la rescata: derivarla necesita fecha de nacimiento, y esta
        // lista no publica ninguna. Es el límite de la técnica, y está aquí escrito para que nadie
        // suponga que «ya cruzamos por CURP» cubre esta fuente. Sobre `SAT-69B` se cruza por RFC.
        assertThat(c.conRaiz()).isZero();
        assertThat(c.alcancePct()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("una lista CON CURP cubre sólo una parte, y esa parte se mide")
    void alistWithCurpCoversOnlyAFraction() {
        loads.load("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 9, 1));

        Cobertura c = coverageOf("PEP-MX");

        assertThat(c.conCurp()).isPositive();
        assertThat(c.curpPct())
                .as("ni 0 ni 1: es una fracción, y por eso hay que mirarla")
                .isGreaterThan(BigDecimal.ZERO)
                .isLessThan(BigDecimal.ONE);
    }

    @Test
    @DisplayName("la cobertura queda guardada en la versión, no sólo en un log")
    void coverageIsPersistedOnTheVersion() {
        // Guardada es comparable entre cargas: si cae de golpe, el publicador dejó de mandar el
        // identificador y el cruce por documento se degradó sin que nada fallara.
        loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        Cobertura c = coverageOf("OFAC-SDN");

        assertThat(c.aceptadas()).isPositive();
        assertThat(c.conCurp()).isNotNull();
        assertThat(c.conRfc()).isNotNull();
    }
    // ── Lo que la raíz derivada recupera ─────────────────────────────────────────────────────

    @Test
    @DisplayName("la raíz derivada sube el alcance por encima de lo que el publicador entrega")
    void thederivedRootRaisesTheReach() {
        // Es la afirmación entera de la derivación, convertida en un número comparable: la lista
        // entrega un CURP en una fracción de sus entradas, y la raíz alcanza a más que eso.
        //
        // Sin esta prueba, «ahora también cruzamos por raíz» es una frase: si la lista no trajera
        // fechas, no se derivaría ninguna y el cruce nuevo no encontraría absolutamente nada.
        loads.load("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 9, 1));

        Cobertura c = coverageOf("PEP-MX");

        assertThat(c.conRaiz())
                .as("las entradas sin CURP que ganaron una raíz")
                .isPositive();
        assertThat(c.alcancePct())
                .as("el alcance real supera a lo publicado")
                .isGreaterThan(c.curpPct());
    }

    @Test
    @DisplayName("la raíz sólo se deriva donde falta el CURP: no se duplica sobre lo que ya identifica")
    void therootIsOnlyDerivedWhereTheCurpIsMissing() {
        loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        Cobertura c = coverageOf("OFAC-SDN");

        // Ninguna entrada cuenta dos veces: las que traen CURP no llevan raíz derivada, así que la
        // suma no puede pasar del total. Si pasara, el alcance estaría inflado y nadie lo notaría.
        assertThat(c.conCurp() + c.conRaiz()).isLessThanOrEqualTo(c.aceptadas());

        Integer conRaizYCurp = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM compliance.watchlist_entries e
                  JOIN compliance.watchlist_versions v ON v.version_id = e.version_id
                 WHERE v.source_code = 'OFAC-SDN' AND v.status = 'ACTIVE'
                   AND e.curp IS NOT NULL AND e.curp_root_a IS NOT NULL
                """, Integer.class);
        assertThat(conRaizYCurp).isZero();
    }

}
