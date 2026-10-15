package com.fintech.compliance.infrastructure.job;

import com.fintech.compliance.application.port.out.RescreenRunRepository;
import com.fintech.compliance.application.port.out.WatchlistVersionRepository;
import com.fintech.compliance.application.service.RescreenService;
import com.fintech.compliance.domain.RescreenRun;
import com.fintech.compliance.domain.RescreenRunStatus;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Dispara el re-cruce en la ventana de mantenimiento.
 *
 * <h2>Por qué en ventana y no al activar la lista</h2>
 *
 * <b>Decisión del negocio (2026-09-14).</b> Al activarse una lista nueva los veredictos vigentes
 * siguen valiendo: nada se detiene, y se asume la ventana entre la activación y el fin del
 * re-cruce. La mitigación es <b>cuándo</b> corre — en la ventana de mantenimiento, cuando no hay
 * disposiciones en vuelo, la exposición real de esa ventana es prácticamente cero.
 *
 * <p>Correrlo al instante de activar tendría el efecto contrario al buscado: cargaría la base en
 * horario de operación, y la ventana seguiría existiendo igual — sólo que en el peor momento.
 *
 * <h2>Y por qué no es «un @Scheduled que revisa cuentas»</h2>
 *
 * La plataforma evita los temporizadores que consultan el estado del negocio; los disparos son
 * hechos. Éste es distinto y conviene decirlo: no decide nada sobre ninguna cuenta ni reacciona a
 * un hecho de negocio. Es una <b>cadencia de ingesta</b> —volver a mirar un dato externo que cambia
 * por su cuenta— y eso es exactamente para lo que sirve un calendario. El hecho de negocio ya
 * ocurrió cuando el gobierno publicó la lista; nosotros no nos enteramos por un evento.
 */
@Component
public class RescreenScheduler {

    private static final Logger log = LoggerFactory.getLogger(RescreenScheduler.class);

    private final RescreenService rescreen;
    private final RescreenRunRepository runs;
    private final WatchlistVersionRepository versions;
    private final boolean enabled;

    public RescreenScheduler(RescreenService rescreen,
                             RescreenRunRepository runs,
                             WatchlistVersionRepository versions,
                             @Value("${fintech.compliance.rescreen.enabled:true}") boolean enabled) {
        this.rescreen = rescreen;
        this.runs     = runs;
        this.versions = versions;
        this.enabled  = enabled;
    }

    /**
     * Ventana de mantenimiento. Por omisión, 03:00 hora de México.
     *
     * <p>La zona se declara y no se hereda del sistema: «las tres» tiene que ser las tres donde
     * está la operación, no donde está el contenedor. Es el mismo error de huso que ya costó dos
     * pruebas en este repo.
     */
    @Scheduled(cron = "${fintech.compliance.rescreen.cron:0 0 3 * * *}", zone = "America/Mexico_City")
    public void runInMaintenanceWindow() {
        if (!enabled) {
            log.info("[RE-CRUCE] apagado por configuración — no corre");
            return;
        }

        Optional<Instant> ultima = lastCompletedAt();
        boolean hayListaNueva = versions.findLastActivatedAfter(ultima.orElse(Instant.EPOCH)).isPresent();

        // Se corre siempre, haya o no lista nueva, y no es desperdicio: las listas se corrigen sin
        // cambiar de versión, y una corrida que no encuentra nada es la prueba barata de que el
        // control sigue vivo. Un control que sólo corre «cuando hace falta» es indistinguible de
        // uno apagado hasta el día que hacía falta.
        String disparo = hayListaNueva
                ? RescreenService.TRIGGER_LIST_ACTIVATED
                : RescreenService.TRIGGER_SCHEDULED;

        log.info("[RE-CRUCE] ventana de mantenimiento — disparo {} (última corrida completa: {})",
                disparo, ultima.map(Instant::toString).orElse("nunca"));

        try {
            RescreenRun run = rescreen.rescreenAll(null, disparo);
            if (run.getChangedCount() > 0) {
                log.warn("[RE-CRUCE] {} persona(s) cambiaron de veredicto — hay cola que adjudicar",
                        run.getChangedCount());
            }
        } catch (RuntimeException e) {
            // No se relanza: tumbar el scheduler dejaría el control apagado hasta el próximo
            // despliegue, que es peor que una corrida perdida. La corrida queda FAILED con motivo
            // y la siguiente vuelve a mirar a todos.
            log.error("[RE-CRUCE] la corrida de mantenimiento falló: {}", e.toString());
        }
    }

    private Optional<Instant> lastCompletedAt() {
        return runs.findRecent(50).stream()
                .filter(r -> r.getStatus() == RescreenRunStatus.COMPLETED)
                .map(RescreenRun::getFinishedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo);
    }
}
