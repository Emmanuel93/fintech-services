package com.fintech.compliance.infrastructure.job;

import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.infrastructure.config.WatchlistSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Trae las listas de gobierno: al arrancar y cada madrugada.
 *
 * <h2>Por qué existe</h2>
 *
 * La carga estaba construida —idempotente por hash, con freno ante un layout que cambió— y no la
 * llamaba nadie. Sin listas vigentes el screening de cada alta quedaba {@code PENDING}, que bloquea
 * en todas las compuertas: correcto como fallo cerrado, pero era un control que no controlaba nada
 * y detenía a todo el mundo.
 *
 * <h2>Por qué al arrancar</h2>
 *
 * Un servicio recién levantado sin listas deja pasar la ventana hasta la madrugada con todas las
 * altas en {@code PENDING}. Cargar al arrancar cuesta poco —si el contenido no cambió, la carga no
 * hace nada— y cierra esa ventana.
 *
 * <h2>Y por qué no relanza</h2>
 *
 * Una lista que no baja no debe tumbar a las otras ni al servicio: queda en el log con su motivo, y
 * la versión anterior sigue vigente — que es exactamente lo que garantiza la carga.
 */
@Component
public class WatchlistSyncJob {

    private static final Logger log = LoggerFactory.getLogger(WatchlistSyncJob.class);
    private static final ZoneId OPERACION = ZoneId.of("America/Mexico_City");

    private final WatchlistLoadService loader;
    private final WatchlistSources sources;
    private final boolean enabled;
    private final boolean onStartup;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WatchlistSyncJob(WatchlistLoadService loader, WatchlistSources sources,
                            @Value("${fintech.compliance.watchlist-sync.enabled:true}") boolean enabled,
                            @Value("${fintech.compliance.watchlist-sync.on-startup:true}") boolean onStartup) {
        this(loader, sources, enabled, onStartup, Clock.system(OPERACION));
    }

    WatchlistSyncJob(WatchlistLoadService loader, WatchlistSources sources,
                     boolean enabled, boolean onStartup, Clock clock) {
        this.loader    = loader;
        this.sources   = sources;
        this.enabled   = enabled;
        this.onStartup = onStartup;
        this.clock     = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        if (enabled && onStartup) syncAll();
    }

    @SchedulerLock(name = "compliance.watchlist-sync", lockAtMostFor = "PT2H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${fintech.compliance.watchlist-sync.cron:0 30 2 * * *}", zone = "${fintech.business.zone:America/Mexico_City}")
    public void nightly() {
        if (enabled) syncAll();
    }

    /** Sincroniza todas las listas declaradas con la publicación de hoy. Devuelve qué pasó con cada una. */
    public List<WatchlistLoadService.LoadResult> syncAll() {
        LocalDate hoy = LocalDate.now(clock);
        List<WatchlistLoadService.LoadResult> resultados = new ArrayList<>();
        if (sources.watchlists().isEmpty()) {
            log.error("[LISTAS] no hay ninguna lista declarada en fintech.compliance.watchlists — el screening no tiene contra qué cruzar");
            return resultados;
        }
        for (WatchlistSources.Source s : sources.watchlists()) {
            try {
                resultados.add(loader.load(s.sourceCode(), s.kind(), hoy));
            } catch (RuntimeException e) {
                log.error("[LISTAS] {} no se pudo sincronizar: {} — sigue vigente la versión anterior",
                        s.sourceCode(), e.toString());
            }
        }
        log.info("[LISTAS] sincronización {} — {}", hoy, resultados.stream()
                .map(r -> r.sourceCode() + "=" + r.status()).toList());
        return resultados;
    }
}
