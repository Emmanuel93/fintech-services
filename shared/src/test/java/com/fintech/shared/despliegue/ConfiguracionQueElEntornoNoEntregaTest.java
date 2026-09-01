package com.fintech.shared.despliegue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ninguna capacidad debe apagarse sola porque el despliegue no entrega su configuración.
 *
 * <p>La forma del defecto es {@code ${VARIABLE:}} — una propiedad con <b>default vacío</b>. El
 * servicio arranca sano, el health queda UP, y la capacidad que dependía de esa variable
 * simplemente no funciona. Nadie se entera hasta que alguien la usa.
 *
 * <p>Pasó de verdad: {@code credit-product-service} validaba un JWT contra
 * {@code ${JWT_SECRET:}}. Compose nunca se lo entregaba, así que el secreto era la cadena vacía,
 * <b>todo</b> token resultaba inválido y cada escritura del catálogo respondía 401 sin cuerpo ni
 * log. Crear, activar o retirar un producto desde el backoffice era imposible. La prueba de
 * integración del servicio no podía verlo porque ella misma inyectaba el secreto que producción no
 * tenía: probaba el filtro bajo una premisa que el despliegue jamás cumplía.
 *
 * <p>Este guardián no juzga el código: compara lo que cada servicio <em>pide</em> contra lo que
 * Compose <em>entrega</em>. Cuando el blanco es legítimo hay que declararlo abajo, con su motivo —
 * la lista obliga a que alguien lo escriba, que es justo lo que faltó.
 */
class ConfiguracionQueElEntornoNoEntregaTest {

    /**
     * Variables que pueden quedar en blanco, y por qué.
     *
     * <p>Formato: {@code servicio/VARIABLE}. Todo lo que no esté aquí y quede en blanco es un
     * hallazgo, no una omisión tolerada.
     */
    private static final Map<String, String> BLANCO_ACEPTADO = Map.of(
            // El stack local entrega el correo a un buzón de prueba que no pide credenciales.
            // En un entorno real ambas se inyectan; en blanco, el envío es anónimo a propósito.
            "notifications-service/SMTP_USERNAME", "el buzón local no autentica",
            "notifications-service/SMTP_PASSWORD", "el buzón local no autentica");

    /**
     * Y el mismo patrón un nivel más arriba: {@code VAR: ${VAR:-}} en el propio Compose.
     *
     * <p>Este guardián nació mirando sólo los {@code application.yml} y por eso <b>no vio</b> el
     * caso que más caro salió: {@code STP_KEK_LOCAL: ${STP_KEK_LOCAL:-}}. Compose declaraba la
     * variable, así que la comprobación de arriba la daba por entregada; el default vacío hacía que
     * el conector no pudiera guardar ninguna llave de firma, y el carril del dinero no podía
     * completarse en local. Nada decía que faltara una variable.
     */
    private static final Pattern DEFAULT_VACIO_EN_COMPOSE =
            Pattern.compile("^\\s*([A-Z][A-Z0-9_]*):\\s*\\$\\{[A-Z][A-Z0-9_]*:-\\s*}\\s*$");

    /** Variables que Compose puede dejar en blanco, y por qué. */
    private static final Map<String, String> BLANCO_ACEPTADO_EN_COMPOSE = Map.of(
            "SMTP_USERNAME", "el buzón local no autentica",
            "SMTP_PASSWORD", "el buzón local no autentica",
            // El blanco es el valor correcto: sin código fijo, el OTP se valida de verdad. Ponerlo
            // es lo que abre la puerta de atrás, y por eso ese es el lado que exige una decisión.
            "OTP_DEV_CODE", "en blanco el OTP se valida de verdad; el valor es lo excepcional");

    /** {@code ${VARIABLE:}} y {@code ${VARIABLE:valor}} — capturamos nombre y default. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):([^}]*)}");

    @Test
    @DisplayName("Toda variable con default vacío la entrega docker-compose, o está declarada como blanco aceptado")
    void ninguna_capacidad_se_apaga_por_configuracion_ausente() throws IOException {
        Path raiz = raizDelRepo();
        Path compose = raiz.resolve("docker-compose.yml");
        assumeTrue(Files.exists(compose), "sin docker-compose.yml no hay nada que comparar");

        Map<String, Set<String>> entregadas = variablesPorServicio(compose);
        List<String> hallazgos = new ArrayList<>();

        try (Stream<Path> servicios = Files.list(raiz.resolve("services"))) {
            for (Path dir : servicios.filter(Files::isDirectory).sorted().toList()) {
                String servicio = dir.getFileName().toString();
                Path recursos = dir.resolve("src/main/resources");
                if (!Files.isDirectory(recursos)) continue;

                try (Stream<Path> ymls = Files.list(recursos)) {
                    for (Path yml : ymls.filter(p -> p.getFileName().toString().matches("application.*\\.yml")).toList()) {
                        for (String linea : Files.readAllLines(yml)) {
                            Matcher m = PLACEHOLDER.matcher(linea);
                            while (m.find()) {
                                if (!m.group(2).isBlank()) continue;          // tiene default real
                                String variable = m.group(1);
                                if (entregadas.getOrDefault(servicio, Set.of()).contains(variable)) continue;
                                if (BLANCO_ACEPTADO.containsKey(servicio + "/" + variable)) continue;
                                hallazgos.add("%s pide %s y compose no se la entrega — %s: %s"
                                        .formatted(servicio, variable, yml.getFileName(), linea.strip()));
                            }
                        }
                    }
                }
            }
        }

        assertThat(hallazgos)
                .describedAs("""
                        Estas variables quedan en blanco al desplegar. La capacidad que dependa de \
                        ellas no va a funcionar y nada lo va a decir. O compose las entrega, o se \
                        declaran en BLANCO_ACEPTADO con el motivo.""")
                .isEmpty();
    }

    @Test
    @DisplayName("Compose no declara variables con default vacío: declararlas así las da por entregadas sin estarlo")
    void compose_no_promete_lo_que_no_entrega() throws IOException {
        Path compose = raizDelRepo().resolve("docker-compose.yml");
        assumeTrue(Files.exists(compose), "sin docker-compose.yml no hay nada que comprobar");

        List<String> hallazgos = new ArrayList<>();
        int numero = 0;
        for (String linea : Files.readAllLines(compose)) {
            numero++;
            Matcher m = DEFAULT_VACIO_EN_COMPOSE.matcher(linea);
            if (m.matches() && !BLANCO_ACEPTADO_EN_COMPOSE.containsKey(m.group(1))) {
                hallazgos.add("docker-compose.yml:%d declara %s con default vacío".formatted(numero, m.group(1)));
            }
        }

        assertThat(hallazgos).describedAs("""
                Una variable con default vacío queda declarada pero sin valor: el servicio arranca, \
                el health queda UP, y la capacidad que dependía de ella no funciona. Si el valor es \
                un secreto de producción, dale un default obviamente-no-secreto para el stack local \
                —como ya se hizo con JWT_SECRET— o decláralo en BLANCO_ACEPTADO_EN_COMPOSE con su \
                motivo.""").isEmpty();
    }

    /** Lo que compose realmente pone en el entorno de cada servicio, resolviendo anclas y merges. */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> variablesPorServicio(Path compose) throws IOException {
        Map<String, Set<String>> porServicio = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(compose)) {
            // El compose reusa anclas (`<<: *otel-agent`) en cada servicio; el tope por defecto
            // de SnakeYAML son 50 y aquí se pasa de largo.
            LoaderOptions opciones = new LoaderOptions();
            opciones.setMaxAliasesForCollections(1000);
            Map<String, Object> raiz = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(opciones)).load(in);
            Map<String, Object> servicios = (Map<String, Object>) raiz.getOrDefault("services", Map.of());
            servicios.forEach((nombre, definicion) -> {
                Object env = ((Map<String, Object>) definicion).get("environment");
                porServicio.put(nombre, nombresDe(env));
            });
        }
        return porServicio;
    }

    /** {@code environment} admite mapa ({@code VAR: valor}) y lista ({@code - VAR=valor}). */
    @SuppressWarnings("unchecked")
    private static Set<String> nombresDe(Object env) {
        if (env instanceof Map<?, ?> mapa) {
            return mapa.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        if (env instanceof List<?> lista) {
            return lista.stream().map(String::valueOf)
                    .map(e -> e.contains("=") ? e.substring(0, e.indexOf('=')) : e)
                    .collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    /** El test corre con el directorio del módulo como raíz; el repo es su padre. */
    private static Path raizDelRepo() {
        return Path.of("").toAbsolutePath().getParent();
    }
}
