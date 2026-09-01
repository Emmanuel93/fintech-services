package com.fintech.shared.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contenedores compartidos por <b>toda</b> la corrida de pruebas del módulo.
 *
 * <p><b>Por qué son estáticos y no se paran nunca.</b> Hoy ~36 clases de prueba del monorepo
 * declaran su propio {@code @Container static}, y Testcontainers levanta <b>un Postgres por clase</b>.
 * En una corrida completa eso son decenas de contenedores, arrancados y tirados en serie, y es la
 * mayor parte del tiempo de la suite.
 *
 * <p>Aquí se levantan una vez por JVM y se reutilizan. No se cierran a propósito: el
 * <i>shutdown hook</i> de Ryuk —o del propio JVM— los recoge al terminar. Cerrarlos entre clases es
 * justo lo que hace lenta la suite.
 *
 * <p><b>El aislamiento no viene de reiniciar el contenedor, viene de limpiar el esquema.</b> Ver
 * {@link AbstractIntegrationTest#limpiarEsquema}.
 */
public final class SharedContainers {

    private SharedContainers() {}

    /** Postgres 16, la misma versión que corre en `docker-compose.yml`. */
    public static final PostgreSQLContainer<?> POSTGRES;

    /** Redis 7, para el candado distribuido y las cachés. */
    public static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("fintech")
                .withUsername("fintech")
                .withPassword("fintech")
                // Reutilizable entre corridas si el desarrollador lo habilita en ~/.testcontainers.properties
                .withReuse(true);
        POSTGRES.start();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withReuse(true);
        REDIS.start();
    }

    public static String redisHost() { return REDIS.getHost(); }
    public static int redisPort()    { return REDIS.getMappedPort(6379); }
}
