package com.fintech.shared.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Los crones corren en la hora de la operación, una sola vez, y la fecha de negocio no sale del
 * contenedor.
 *
 * <p>Los contenedores corren en UTC. Un {@code @Scheduled} sin {@code zone} disparaba seis horas
 * antes en México —el devengo a las 17:00— y {@code LocalDate.now()} daba «mañana» desde las 18:00:
 * {@code InstallmentDueJob} publicaba como vencidas cuotas que vencían al día siguiente. Con varias
 * réplicas, además, cada pod corría su propio cron: la facturación del día 1 pedía cada CFDI dos
 * veces.
 *
 * <p>Este guardián no juzga lo que hace cada job; vigila tres reglas que nadie debería volver a
 * olvidar:
 * <ol>
 *   <li>todo {@code @Scheduled(cron = ...)} declara {@code zone};</li>
 *   <li>todo {@code @Scheduled(cron = ...)} lleva {@code @SchedulerLock};</li>
 *   <li>nadie calcula la fecha con el reloj del contenedor ({@code LocalDate.now()} sin argumento,
 *       {@code ZoneId.systemDefault()}): se usa {@code BusinessClock}.</li>
 *   <li>ningún cron diario dispara entre las 23:00 y las 00:04: el día se procesa cuando terminó. El
 *       cierre del 10 de noviembre corre el 11 a partir de las 00:05.</li>
 * </ol>
 *
 * <p>Los jobs de intervalo ({@code fixedDelay}) quedan fuera a propósito: o limpian estado en memoria
 * de su propio pod, o reparten trabajo con {@code SKIP LOCKED}, y en ambos casos deben correr en
 * cada réplica.
 */
class ScheduledJobsRunInBusinessTimeTest {

    private static final Pattern CRON = Pattern.compile("@Scheduled\\(\\s*cron\\s*=");
    private static final Pattern CONTAINER_DATE = Pattern.compile(
            "LocalDate\\.now\\(\\)|LocalDateTime\\.now\\(\\)|YearMonth\\.now\\(\\)|ZoneId\\.systemDefault\\(\\)");

    @Test
    void todo_cron_declara_la_zona_de_negocio_y_lleva_candado() throws IOException {
        List<String> hallazgos = new ArrayList<>();
        for (Path java : mainSources()) {
            List<String> lines = Files.readAllLines(java);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (comment(line) || !CRON.matcher(line).find()) continue;
                String where = relative(java) + ":" + (i + 1);
                if (!line.contains("zone")) {
                    hallazgos.add(where + " cron sin zone — corre en la hora del contenedor (UTC)");
                }
                boolean locked = false;
                for (int j = Math.max(0, i - 3); j < i; j++) {
                    locked |= lines.get(j).contains("@SchedulerLock");
                }
                if (!locked) {
                    hallazgos.add(where + " cron sin @SchedulerLock — con varias réplicas corre en cada una");
                }
            }
        }
        assertThat(hallazgos).as("Crones fuera de la hora de negocio o sin candado").isEmpty();
    }

    @Test
    void nadie_calcula_la_fecha_con_el_reloj_del_contenedor() throws IOException {
        List<String> hallazgos = new ArrayList<>();
        for (Path java : mainSources()) {
            List<String> lines = Files.readAllLines(java);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!comment(line) && CONTAINER_DATE.matcher(line).find()) {
                    hallazgos.add(relative(java) + ":" + (i + 1) + " " + line.trim());
                }
            }
        }
        assertThat(hallazgos)
                .as("La fecha de negocio sale de BusinessClock (today(), zone(), clock()), no del contenedor")
                .isEmpty();
    }

    @Test
    void ningun_cron_diario_corre_antes_de_que_termine_el_dia() throws IOException {
        List<String> hallazgos = new ArrayList<>();
        for (Path java : mainSources()) {
            List<String> lines = Files.readAllLines(java);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (comment(line) || !CRON.matcher(line).find()) continue;
                revisarHora(cronOf(line), relative(java) + ":" + (i + 1), hallazgos);
            }
        }
        for (Path yml : configFiles()) {
            List<String> lines = Files.readAllLines(yml);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("#") || !YML_CRON.matcher(line).find()) continue;
                revisarHora(line.substring(line.indexOf(':') + 1), relative(yml) + ":" + (i + 1), hallazgos);
            }
        }
        assertThat(hallazgos)
                .as("El día se procesa cuando terminó: los crones diarios van de las 00:05 en adelante, nunca a las 23:xx")
                .isEmpty();
    }

    @Test
    void revisar_todo_el_codigo_es_rapido() throws IOException {
        long inicio = System.nanoTime();
        int archivos = 0;
        for (Path java : mainSources()) {
            Files.readAllLines(java);
            archivos++;
        }
        // Rendimiento: el guardián recorre todos los servicios en cada build.
        assertThat(archivos).isGreaterThan(100);
        assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(10_000L);
    }

    private static final Pattern YML_CRON = Pattern.compile("^[a-z-]*cron:");

    /** La expresión de un {@code @Scheduled(cron = "...")}; de un {@code ${prop:default}}, el default. */
    private static String cronOf(String line) {
        int a = line.indexOf('"', line.indexOf("cron"));
        int b = line.indexOf('"', a + 1);
        return a < 0 || b < 0 ? "" : line.substring(a + 1, b);
    }

    /**
     * Segundos, minutos, hora, ...: si la hora es fija (un cron diario, semanal o mensual) no puede caer
     * entre las 23:00 y las 00:04. Los que corren cada hora ({@code *}) quedan fuera.
     */
    private static void revisarHora(String expr, String where, List<String> hallazgos) {
        String e = expr.replace("\"", "").trim();
        if (e.startsWith("${")) {
            int colon = e.indexOf(':');
            if (colon < 0) return;
            e = e.substring(colon + 1, e.lastIndexOf('}') > colon ? e.lastIndexOf('}') : e.length()).trim();
        }
        String[] f = e.split("\\s+");
        if (f.length < 3 || !f[1].matches("\\d+") || !f[2].matches("\\d+")) return;
        int minuto = Integer.parseInt(f[1]), hora = Integer.parseInt(f[2]);
        if (hora == 23 || (hora == 0 && minuto < 5)) {
            hallazgos.add(where + " cron «" + e + "» corre a las " + String.format("%02d:%02d", hora, minuto)
                          + " — el día todavía no termina o apenas terminó");
        }
    }

    private static List<Path> configFiles() throws IOException {
        Path services = repoRoot().resolve("services");
        try (Stream<Path> files = Files.walk(services)) {
            return files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .filter(p -> p.toString().contains("/src/main/resources/"))
                    .sorted()
                    .toList();
        }
    }

    private static boolean comment(String line) {
        String t = line.trim();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    private static List<Path> mainSources() throws IOException {
        Path services = repoRoot().resolve("services");
        assumeTrue(Files.isDirectory(services), "sin services/ no hay nada que revisar");
        try (Stream<Path> files = Files.walk(services)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .sorted()
                    .toList();
        }
    }

    private static String relative(Path p) {
        return repoRoot().relativize(p).toString();
    }

    private static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }
}
