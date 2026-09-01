package com.fintech.shared.testing;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

/**
 * Base de las pruebas de integración: Postgres y Redis reales, una sola vez por JVM.
 *
 * <p>Sustituye al bloque de doce líneas que hoy se repite en ~36 archivos —
 * {@code @Container static PostgreSQLContainer<>(...)} más su {@code @DynamicPropertySource}—. Esa
 * duplicación no sólo cuesta mantenimiento: hace que cada clase levante su propio contenedor.
 *
 * <p><b>Cómo se usa.</b> Extender, anotar con {@code @SpringBootTest}, y declarar qué esquemas hay
 * que limpiar entre pruebas:
 *
 * <pre>{@code
 * @SpringBootTest
 * class MiFlujoIT extends AbstractIntegrationTest {
 *     @Override protected List<String> esquemas() { return List.of("closing"); }
 * }
 * }</pre>
 *
 * <p><b>El aislamiento viene del truncado, no del reinicio.</b> Reiniciar el contenedor entre
 * clases sería correcto y lentísimo; truncar las tablas del esquema deja la base igual de limpia y
 * cuesta milisegundos. La excepción son las tablas de Liquibase, que no se tocan: recrear el
 * esquema en cada prueba anularía la ventaja.
 */
public abstract class AbstractIntegrationTest {

    @Autowired(required = false)
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void infraestructura(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      SharedContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", SharedContainers.POSTGRES::getPassword);
        // El driver también, y no es redundante: varios servicios declaran en su perfil `test` el
        // driver de Testcontainers junto a una URL `jdbc:tc:`. Al sobrescribir sólo la URL, ese
        // driver se queda y rechaza la URL corriente con un «claims to not accept jdbcUrl» que no
        // dice en ningún lado que el problema es el driver.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host",     SharedContainers::redisHost);
        registry.add("spring.data.redis.port",     SharedContainers::redisPort);
    }

    /**
     * Los esquemas a limpiar entre pruebas. Vacío = no se limpia nada.
     *
     * <p>Se declara y no se deduce: una prueba que sólo lee no necesita pagar el truncado, y una
     * que escribe en dos esquemas tiene que decirlo.
     */
    protected List<String> esquemas() {
        return List.of();
    }

    /** Tablas que sobreviven siempre. Liquibase lleva aquí su propio estado. */
    private static final List<String> INTOCABLES =
            List.of("databasechangelog", "databasechangeloglock");

    /**
     * Tablas de <b>catálogo</b> que el truncado no debe tocar.
     *
     * <p>La clase base no puede distinguir catálogo de dato transaccional, y equivocarse tiene un
     * síntoma desconcertante: Liquibase siembra políticas, calendarios o parámetros en un changeset,
     * el truncado los borra en la primera prueba, y a partir de ahí <b>todo falla con «no existe la
     * configuración»</b> sin que nadie haya tocado la configuración.
     *
     * <p>Por eso se declara aquí, en la prueba, que es quien sabe qué sembró su changelog.
     */
    protected List<String> preservar() {
        return List.of();
    }

    @BeforeEach
    void limpiarEsquema() {
        if (jdbcTemplate == null || esquemas().isEmpty()) {
            return;
        }
        for (String esquema : esquemas()) {
            List<String> tablas = jdbcTemplate.queryForList("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = ? AND table_type = 'BASE TABLE'
                    """, String.class, esquema);

            List<String> conservar = preservar().stream().map(String::toLowerCase).toList();
            String objetivo = tablas.stream()
                    .filter(t -> !INTOCABLES.contains(t.toLowerCase()))
                    .filter(t -> !conservar.contains(t.toLowerCase()))
                    .map(t -> esquema + "." + t)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);

            if (objetivo != null) {
                // Un solo TRUNCATE con CASCADE: hacerlo tabla por tabla obligaría a conocer el
                // orden de las claves foráneas, que cambia con cada changeset nuevo.
                jdbcTemplate.execute("TRUNCATE TABLE " + objetivo + " RESTART IDENTITY CASCADE");
            }
        }
    }
}
