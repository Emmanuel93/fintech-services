package com.fintech.compliance;

import com.fintech.compliance.domain.CurpRoot;
import com.fintech.compliance.domain.NormalizedName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La raíz del CURP: reconstruirla para las entradas de lista que no traen CURP.
 *
 * <p>El CURP es el identificador que una persona tiene desde que nace, y su parte determinista
 * <b>se calcula</b> del nombre y la fecha. Eso permite algo que ningún otro dato permite: derivarlo
 * para las entradas que no lo publican — que son la mayoría — y convertir un cruce por nombre en uno
 * que incorpora la fecha de nacimiento exacta.
 */
class CurpRootTest {

    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1985, 3, 12);

    private static java.util.Set<String> rootsOf(String nombre) {
        return CurpRoot.candidatesFor(NormalizedName.of(nombre), DATE_OF_BIRTH);
    }

    // ── Lo que deriva ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deriva la raíz de un nombre mexicano completo")
    void itDerivesTheRoot() {
        // PEREZ → P + E (primera vocal interna) · GARCIA → G · JUAN → J · 1985-03-12 → 850312
        assertThat(rootsOf("PEREZ GARCIA JUAN")).contains("PEGJ850312");
    }

    @Test
    @DisplayName("coincide con la raíz de un CURP real")
    void itMatchesARealCurpRoot() {
        // Es el punto: la raíz derivada de la lista se compara con la del CURP de la persona.
        assertThat(CurpRoot.of("PEGJ850312HDFRRN09")).isEqualTo("PEGJ850312");
        assertThat(rootsOf("PEREZ GARCIA JUAN")).contains(CurpRoot.of("PEGJ850312HDFRRN09"));
    }

    // ── Lo que absorbe, y es lo que gana sobre comparar nombres ──────────────────────────────

    @Test
    @DisplayName("absorbe la variante de escritura: GONZÁLEZ y GONZALES dan la misma raíz")
    void itAbsorbsSpellingVariants() {
        // Sin comparación difusa y sin umbral que calibrar: la raíz usa la inicial y la primera
        // vocal interna, y ésas no cambian. Es determinista, que es lo que un control necesita.
        assertThat(rootsOf("GONZALEZ LOPEZ MARIA"))
                .containsAnyElementsOf(rootsOf("GONZALES LOPEZ MARIA"));
    }

    @Test
    @DisplayName("absorbe los nombres compuestos: sólo cuenta la inicial del primero")
    void itAbsorbsCompoundGivenNames() {
        assertThat(rootsOf("PEREZ GARCIA JOSE LUIS"))
                .containsAnyElementsOf(rootsOf("PEREZ GARCIA JOSE"));
    }

    @Test
    @DisplayName("da igual el orden en que la lista escriba nombre y apellidos")
    void theOrderDoesNotMatter() {
        // Las listas mezclan los dos órdenes, a veces en el mismo archivo. Se derivan las dos
        // lecturas porque adivinar cuál es sale más caro que comparar dos cadenas de diez.
        assertThat(rootsOf("PEREZ GARCIA JUAN"))
                .containsAnyElementsOf(rootsOf("JUAN PEREZ GARCIA"));
    }

    // ── Lo que distingue, que es lo que lo hace fuerte ───────────────────────────────────────

    @Test
    @DisplayName("dos homónimos con distinta fecha dan raíces DISTINTAS")
    void twoNamesakesWithDifferentDatesDiffer() {
        // Es lo que un cruce por nombre no puede hacer. «JUAN PEREZ GARCIA» coincide con decenas de
        // personas; «JUAN PEREZ GARCIA nacido el 12/03/1985» con muy pocas.
        var first = CurpRoot.candidatesFor(NormalizedName.of("PEREZ GARCIA JUAN"), LocalDate.of(1985, 3, 12));
        var second = CurpRoot.candidatesFor(NormalizedName.of("PEREZ GARCIA JUAN"), LocalDate.of(1962, 1, 1));

        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    @DisplayName("apellidos distintos dan raíces distintas aunque coincida el nombre")
    void differentSurnamesDiffer() {
        assertThat(rootsOf("PEREZ GARCIA JUAN")).doesNotContainAnyElementsOf(rootsOf("LOPEZ MARTINEZ JUAN"));
    }

    // ── Lo que NO hace ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin fecha de nacimiento no hay raíz que derivar")
    void withoutADateThereIsNoRoot() {
        // Y no se inventa una con ceros: sería una llave que coincide con todos los que tampoco
        // tienen fecha, que es el falso positivo más tonto posible.
        assertThat(CurpRoot.candidatesFor(NormalizedName.of("PEREZ GARCIA JUAN"), null)).isEmpty();
    }

    @Test
    @DisplayName("con un solo token tampoco: no hay apellidos que derivar")
    void withASingleTokenThereIsNoRoot() {
        assertThat(rootsOf("MADONNA")).isEmpty();
    }

    @Test
    @DisplayName("sin apellido materno se usa X, como hace RENAPO")
    void amissingMaternalSurnameBecomesX() {
        assertThat(rootsOf("PEREZ JUAN")).anySatisfy(r -> assertThat(r).startsWith("PEXJ"));
    }

    @Test
    @DisplayName("un CURP corto o nulo no produce raíz — no se adivina")
    void ashortCurpHasNoRoot() {
        assertThat(CurpRoot.of(null)).isNull();
        assertThat(CurpRoot.of("PEGJ85")).isNull();
    }
}
