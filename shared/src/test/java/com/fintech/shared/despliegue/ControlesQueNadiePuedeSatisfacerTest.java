package com.fintech.shared.despliegue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Un control que nombra a alguien que no existe se lee como un control y no lo es.
 *
 * <p>Ha pasado tres veces en este repo, y las tres con el mismo síntoma: un <b>403 sin
 * explicación</b>, indistinguible de un permiso mal configurado, sobre una funcionalidad terminada.
 *
 * <ul>
 *   <li>{@code beneficiaries.view} protegía la bandeja de KYC sin estar sembrada para ningún rol.
 *       Lo arregló el changeset 015, que además dejó escrita la lección.</li>
 *   <li>{@code applications.review-documents} y {@code beneficiaries.review-identity} volvieron a
 *       caer en lo mismo: dos mesas de trabajo que no podían guardar su dictamen.</li>
 *   <li>{@code RISK_MANAGER} custodiaba los programas de apoyo. Ese rol <b>no existe</b> en el
 *       catálogo de identity — nunca se ha emitido. El efecto no fue un 403 ruidoso sino algo peor:
 *       sólo ADMIN pasaba, así que el maker-checker de un apoyo masivo se resolvía entre dos
 *       administradores y la separación por función que el rol nombraba no existía.</li>
 * </ul>
 *
 * <p>Esta prueba compara lo que el código <em>exige</em> contra lo que identity <em>emite</em>. No
 * juzga el reparto de facultades: sólo exige que quien se nombra exista.
 */
class ControlesQueNadiePuedeSatisfacerTest {

    /** {@code requires("clients.view")} en las cadenas de seguridad de los BFF. */
    private static final Pattern CAPACIDAD = Pattern.compile("requires\\(\"([a-z][a-z.-]+)\"\\)");

    /** Los literales de {@code hasRole('X')} y {@code hasAnyRole('X','Y')} en cualquier servicio. */
    private static final Pattern EXPRESION_DE_ROL = Pattern.compile("has(?:Any)?Role\\(([^)]*)\\)");
    private static final Pattern ROL = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    /** Lo que identity siembra: {@code ('ADMIN','clients.view','seed')}. */
    private static final Pattern SEMBRADO =
            Pattern.compile("\\(\\s*'([A-Z][A-Z0-9_]*)'\\s*,\\s*'([a-z][a-z.-]+)'");

    @Test
    @DisplayName("Toda capacidad que el backoffice exige está sembrada para algún rol")
    void ninguna_ruta_pide_una_capacidad_que_nadie_tiene() throws IOException {
        Path raiz = raizDelRepo();
        assumeTrue(Files.isDirectory(raiz.resolve("services")), "sin services/ no hay nada que comparar");

        Catalogo catalogo = catalogoDeIdentity(raiz);
        List<String> hallazgos = new ArrayList<>();

        for (Path java : fuentesJava(raiz)) {
            String texto = Files.readString(java);
            Matcher m = CAPACIDAD.matcher(texto);
            while (m.find()) {
                if (!catalogo.capacidades.contains(m.group(1))) {
                    hallazgos.add("%s exige la capacidad '%s', que identity no siembra para ningún rol"
                            .formatted(raiz.relativize(java), m.group(1)));
                }
            }
        }

        assertThat(hallazgos).describedAs("""
                Una ruta protegida por una capacidad que nadie tiene no está protegida, está rota: \
                responde 403 a todo el mundo, incluido ADMIN, y el síntoma es indistinguible de un \
                permiso mal configurado. Siémbrala en un changeset de identity para los roles que \
                ya hacen ese trabajo.""").isEmpty();
    }

    @Test
    @DisplayName("Todo rol que un @PreAuthorize nombra existe en el catálogo de identity")
    void ningun_control_nombra_un_rol_inexistente() throws IOException {
        Path raiz = raizDelRepo();
        assumeTrue(Files.isDirectory(raiz.resolve("services")), "sin services/ no hay nada que comparar");

        Catalogo catalogo = catalogoDeIdentity(raiz);
        List<String> hallazgos = new ArrayList<>();

        for (Path java : fuentesJava(raiz)) {
            String texto = Files.readString(java);
            Matcher expresion = EXPRESION_DE_ROL.matcher(texto);
            while (expresion.find()) {
                Matcher rol = ROL.matcher(expresion.group(1));
                while (rol.find()) {
                    if (!catalogo.roles.contains(rol.group(1))) {
                        hallazgos.add("%s exige el rol %s, que identity nunca emite"
                                .formatted(raiz.relativize(java), rol.group(1)));
                    }
                }
            }
        }

        assertThat(hallazgos).describedAs("""
                Un control que nombra un rol inexistente no falla ruidosamente: simplemente nadie \
                lo cumple nunca, y el resto de la expresión decide sola. Donde el rol acompañaba a \
                ADMIN, el efecto es que la facultad quedó sólo en ADMIN y la separación por función \
                que el rol nombraba no existe.""").isEmpty();
    }

    // ── Lo que identity emite ────────────────────────────────────────────────

    private record Catalogo(Set<String> roles, Set<String> capacidades) {}

    private static Catalogo catalogoDeIdentity(Path raiz) throws IOException {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> capacidades = new LinkedHashSet<>();
        Path changelogs = raiz.resolve("services/identity-service/src/main/resources/db/changelog/identity");
        assumeTrue(Files.isDirectory(changelogs), "sin los changesets de identity no hay catálogo");

        try (Stream<Path> sql = Files.list(changelogs)) {
            for (Path fichero : sql.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                Matcher m = SEMBRADO.matcher(Files.readString(fichero));
                while (m.find()) {
                    roles.add(m.group(1));
                    capacidades.add(m.group(2));
                }
            }
        }
        return new Catalogo(roles, capacidades);
    }

    private static List<Path> fuentesJava(Path raiz) throws IOException {
        try (Stream<Path> arbol = Files.walk(raiz.resolve("services"))) {
            return arbol.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .toList();
        }
    }

    private static Path raizDelRepo() {
        return Path.of("").toAbsolutePath().getParent();
    }
}
