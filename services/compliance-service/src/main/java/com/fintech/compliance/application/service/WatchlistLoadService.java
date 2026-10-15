package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.*;
import com.fintech.compliance.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Carga una lista de gobierno: la baja, la lee, la guarda como versión nueva y —si merece— la activa.
 *
 * <h2>El orden importa, y cada paso está donde está por una razón</h2>
 *
 * <pre>
 * 1. bajar a disco calculando el hash   ← el archivo no cabe en memoria, y el hash necesita todo
 * 2. ¿ya se cargó ese hash?             ← idempotencia: la misma publicación dos veces no hace nada
 * 3. leer, contando buenas y malas      ← una fila mala no tumba la carga
 * 4. terminar                           ← cero filas buenas ⇒ falla, no «lista vacía»
 * 5. ¿la tasa de rechazo se disparó?    ← si sí, NO se activa: el layout cambió
 * 6. activar, jubilando la anterior     ← atómico: nunca hay dos vigentes ni ninguna
 * </pre>
 */
@Service
public class WatchlistLoadService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistLoadService.class);

    /** Lotes de inserción: ni una fila a la vez ni el archivo entero en memoria. */
    private static final int BATCH = 500;

    /**
     * Cuánto puede empeorar la tasa de rechazo respecto a la carga anterior antes de no activar.
     *
     * <p>Veinte puntos porcentuales. No es una cifra fina y no pretende serlo: separa «el archivo de
     * hoy trae algunas filas más sucias» de «esto ya no es el mismo formato». Una carga que pasaba
     * del 2 % al 40 % de rechazo no es una lista peor, es otra lista — y activarla dejaría fuera al
     * 38 % de quienes deberían estar dentro, sin que nada falle.
     */
    private static final BigDecimal SALTO_MAXIMO_DE_RECHAZO = new BigDecimal("0.20");

    private final GovernmentListPort government;
    private final WatchlistVersionRepository versions;
    private final WatchlistEntryRepository entries;
    private final RowRejectionRepository rejections;
    private final VersionChangeRepository changes;

    public WatchlistLoadService(GovernmentListPort government,
                                WatchlistVersionRepository versions,
                                WatchlistEntryRepository entries,
                                RowRejectionRepository rejections,
                                VersionChangeRepository changes) {
        this.government = government;
        this.versions   = versions;
        this.entries    = entries;
        this.rejections = rejections;
        this.changes    = changes;
    }

    /** El resultado de intentar cargar, para que quien la dispare sepa qué pasó sin leer el log. */
    public record LoadResult(String sourceCode, WatchlistVersionStatus status, String fileHash,
                             int totalRows, int acceptedRows, int rejectedRows, String detail) {

        public boolean activated()   { return status == WatchlistVersionStatus.ACTIVE; }
        public boolean skipped()     { return detail != null && detail.startsWith("YA_CARGADA"); }
    }

    @Transactional
    public LoadResult load(String sourceCode, WatchlistKind kind, LocalDate publishedOn) {
        Path temp = null;
        try {
            // ── 1 · bajar a disco calculando el hash ────────────────────────────────────────
            temp = Files.createTempFile("watchlist-" + sourceCode + "-", ".csv");
            String hash = downloadAndHash(sourceCode, kind, publishedOn, temp);

            // ── 2 · idempotencia ───────────────────────────────────────────────────────────
            Optional<WatchlistVersion> previa = versions.findByFileHash(hash);
            if (previa.isPresent() && previa.get().getStatus() != WatchlistVersionStatus.FAILED) {
                WatchlistVersion v = previa.get();
                log.info("[LISTAS] {} publicada {} ya estaba cargada (hash {}…, estado {}) — no se hace nada",
                        sourceCode, publishedOn, hash.substring(0, 12), v.getStatus());
                return new LoadResult(sourceCode, v.getStatus(), hash, v.getTotalRows(),
                        v.getAcceptedRows(), v.getRejectedRows(),
                        "YA_CARGADA: mismo contenido que la versión " + v.getVersionId());
            }

            // La referencia para comparar la suciedad se toma AHORA, antes de guardar la nueva.
            // Buscarla al final es ambiguo: la propia versión recién guardada es «la última
            // terminada» y se compara consigo misma, que siempre da cero salto — el freno no
            // frenaría nunca y nada lo delataría.
            BigDecimal rechazoAnterior = versions.findLastFinished(sourceCode)
                    .map(WatchlistVersion::rejectionRate)
                    .orElse(null);

            WatchlistVersion version = versions.save(
                    WatchlistVersion.start(sourceCode, kind, hash, sourceCode + "-" + publishedOn + ".csv", publishedOn));

            // ── 3 · leer ───────────────────────────────────────────────────────────────────
            readInto(version, temp, kind);

            // ── 4 · terminar ───────────────────────────────────────────────────────────────
            version.finishLoaded();
            versions.save(version);

            // ── 5 · ¿el layout cambió? ─────────────────────────────────────────────────────
            Optional<String> freno = rejectionJumped(rechazoAnterior, version);
            if (freno.isPresent()) {
                log.error("[LISTAS] {} NO se activa: {}", sourceCode, freno.get());
                return new LoadResult(sourceCode, version.getStatus(), hash, version.getTotalRows(),
                        version.getAcceptedRows(), version.getRejectedRows(), freno.get());
            }

            // ── 6 · activar ────────────────────────────────────────────────────────────────
            activate(version);

            log.info("[LISTAS] {} v{} ACTIVA — {} filas, {} aceptadas, {} rechazadas ({}%) · proveedor {}",
                    sourceCode, version.getVersionId(), version.getTotalRows(),
                    version.getAcceptedRows(), version.getRejectedRows(),
                    version.rejectionRate().movePointRight(2), government.describeProvider());

            // La cobertura se registra en cada carga y no en un informe aparte: es el número que
            // dice contra QUÉ FRACCIÓN de la lista puede encontrar algo un cruce por documento, y
            // si baja de golpe significa que el publicador dejó de mandar el identificador.
            log.info("[LISTAS] {} · cobertura de identificadores — CURP {}% · RFC {}% · fecha {}/{} "
                     + "· raíz derivada {} ⇒ alcance por CURP {}%",
                    sourceCode, version.curpCoverage().movePointRight(2),
                    version.rfcCoverage().movePointRight(2),
                    version.getEntriesWithDob(), version.getAcceptedRows(),
                    version.getEntriesWithCurpRoot(), version.curpReachCoverage().movePointRight(2));

            // El aviso mira el ALCANCE, no lo que el publicador entrega: lo que importa no es que la
            // lista traiga pocos CURP, sino que el cruce por CURP no llegue a la mayoría de ella.
            if (version.curpReachCoverage().compareTo(new BigDecimal("0.50")) < 0) {
                log.warn("[LISTAS] {} · el cruce por CURP sólo alcanza al {}% de las entradas "
                         + "({}% con CURP publicado + {} con raíz derivada): el resto sale limpio "
                         + "de ese cruce sin que nada falle, y sólo lo cubre el cruce por nombre",
                        sourceCode, version.curpReachCoverage().movePointRight(2),
                        version.curpCoverage().movePointRight(2), version.getEntriesWithCurpRoot());
            }

            return new LoadResult(sourceCode, version.getStatus(), hash, version.getTotalRows(),
                    version.getAcceptedRows(), version.getRejectedRows(), "ACTIVADA");

        } catch (EmptyWatchlistLoadException e) {
            // Tiene tipo propio porque la respuesta no es reintentar: es mirar el archivo.
            log.error("[LISTAS] {} falló: {}", sourceCode, e.getMessage());
            throw e;
        } catch (IOException e) {
            throw new WatchlistLoadFailedException(sourceCode, "no se pudo leer el archivo", e);
        } finally {
            deleteQuietly(temp);
        }
    }

    // ── Pasos ────────────────────────────────────────────────────────────────────────────────

    private String downloadAndHash(String sourceCode, WatchlistKind kind,
                                   LocalDate publishedOn, Path temp) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);   // no ocurre en una JVM estándar
        }

        try (InputStream in = government.download(sourceCode, kind, publishedOn);
             DigestInputStream digested = new DigestInputStream(in, sha);
             OutputStream out = Files.newOutputStream(temp)) {
            digested.transferTo(out);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private void readInto(WatchlistVersion version, Path temp, WatchlistKind kind) throws IOException {
        CsvLayout layout = government.layoutFor(version.getSourceCode());
        WatchlistCsvParser parser = new WatchlistCsvParser(layout);

        List<WatchlistEntry> loteFilas = new ArrayList<>(BATCH);
        List<RowRejection>   loteMalas = new ArrayList<>(BATCH);

        // Se cuenta al pasar, no con una consulta después: el archivo ya se está recorriendo, y una
        // segunda pasada sobre cientos de miles de filas para contar tres campos no se justifica.
        int[] cobertura = new int[4];   // curp, rfc, fecha, raíz derivada

        try (InputStream in = Files.newInputStream(temp)) {
            parser.parse(in, version.getVersionId(), kind,
                    (entry, line) -> {
                        version.countRow(true);
                        if (entry.getCurp() != null)        cobertura[0]++;
                        if (entry.getRfc() != null)         cobertura[1]++;
                        if (entry.getDateOfBirth() != null) cobertura[2]++;
                        if (!entry.curpRoots().isEmpty())   cobertura[3]++;
                        loteFilas.add(entry);
                        if (loteFilas.size() >= BATCH) { entries.saveAll(List.copyOf(loteFilas)); loteFilas.clear(); }
                    },
                    rejection -> {
                        version.countRow(false);
                        loteMalas.add(RowRejection.of(version.getVersionId(),
                                rejection.line(), rejection.reason(), rejection.raw()));
                        if (loteMalas.size() >= BATCH) { rejections.saveAll(List.copyOf(loteMalas)); loteMalas.clear(); }
                    });
        } catch (IllegalStateException e) {
            // Cabecera que no trae la columna de nombre: el layout ya no corresponde al archivo.
            version.fail(e.getMessage());
            versions.save(version);
            entries.deleteByVersionId(version.getVersionId());
            throw new WatchlistLoadFailedException(version.getSourceCode(), e.getMessage(), e);
        }

        if (!loteFilas.isEmpty()) entries.saveAll(loteFilas);
        if (!loteMalas.isEmpty()) rejections.saveAll(loteMalas);

        version.recordIdentifierCoverage(cobertura[0], cobertura[1], cobertura[2], cobertura[3]);
    }

    /**
     * Compara la suciedad de esta carga con la de la anterior.
     *
     * <p>No mira la tasa absoluta —hay listas que siempre traen un 15 % de basura y está bien— sino
     * el <b>salto</b>. Lo que delata un layout cambiado no es que haya rechazos, es que de pronto
     * haya muchos más.
     *
     * <p>Se frena antes de activar y no después: una vez activa, ya está decidiendo sobre personas.
     */
    private Optional<String> rejectionJumped(BigDecimal rechazoAnterior, WatchlistVersion nueva) {
        // Sin carga anterior no hay con qué comparar: la primera vez se activa y su tasa queda como
        // la línea base de la siguiente.
        if (rechazoAnterior == null) return Optional.empty();

        BigDecimal salto = nueva.rejectionRate().subtract(rechazoAnterior);
        if (salto.compareTo(SALTO_MAXIMO_DE_RECHAZO) <= 0) return Optional.empty();

        return Optional.of("la tasa de rechazo saltó de " + rechazoAnterior.movePointRight(2)
                + "% a " + nueva.rejectionRate().movePointRight(2)
                + "%: el layout cambió. La versión queda LOADED sin activar — "
                + "revisa los rechazos de " + nueva.getVersionId());
    }

    /**
     * Activa la nueva y jubila la anterior — en ese orden y no antes.
     *
     * <p>Hasta este momento la anterior sigue siendo la vigente: una carga a medias nunca deja a la
     * plataforma sin lista. Y justo aquí, con las dos versiones presentes, es el <b>único</b>
     * momento en que se puede calcular qué cambió: después, la comparación seguiría siendo posible
     * pero nadie la pediría, y el detalle de la actualización es lo que contesta «¿por qué esta
     * persona quedó señalada el día 14?».
     */
    private void activate(WatchlistVersion nueva) {
        var anterior = versions.findBySourceCodeAndStatus(
                nueva.getSourceCode(), WatchlistVersionStatus.ACTIVE);

        recordChanges(nueva, anterior.map(WatchlistVersion::getVersionId).orElse(null));

        anterior.ifPresent(vieja -> {
            vieja.supersede();
            versions.save(vieja);
        });
        nueva.activate();
        versions.save(nueva);
    }

    /**
     * El detalle de la actualización.
     *
     * <p>La primera carga de una lista <b>no</b> se detalla entrada por entrada: serían cientos de
     * miles de filas diciendo «todo es nuevo». Es la línea base, y lo que un revisor mira son los
     * cambios contra ella.
     */
    private void recordChanges(WatchlistVersion nueva, UUID anteriorId) {
        if (anteriorId == null) {
            changes.recordInitialLoad(
                    WatchlistVersionChange.initialLoad(nueva.getVersionId(), nueva.getAcceptedRows()));
            log.info("[LISTAS] {} · primera carga, {} entradas — sin diff que calcular",
                    nueva.getSourceCode(), nueva.getAcceptedRows());
            return;
        }
        int total = changes.computeAndStore(nueva.getVersionId(), anteriorId);
        var porTipo = changes.countByType(nueva.getVersionId());
        log.info("[LISTAS] {} · actualización: {} cambios (altas {}, bajas {}, modificadas {})",
                nueva.getSourceCode(), total,
                porTipo.getOrDefault("ADDED", 0L), porTipo.getOrDefault("REMOVED", 0L),
                porTipo.getOrDefault("MODIFIED", 0L));
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("No se pudo borrar el temporal {}: {}", p, e.toString());
        }
    }
}
