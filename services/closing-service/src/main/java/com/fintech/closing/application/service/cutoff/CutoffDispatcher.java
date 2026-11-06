package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manda al tópico de trabajo las unidades de una corrida, una por cuenta.
 *
 * <h2>Por qué por Kafka y no con cada pod leyendo la tabla</h2>
 *
 * Con la tabla, cada réplica pregunta «¿qué queda?» y compite por las mismas filas: el candado
 * rechaza casi todos los intentos y se gasta más en coordinar que en trabajar. Con el tópico, Kafka
 * reparte las particiones entre los pods del grupo y cada cuenta llega a uno solo. Agregar pods
 * acelera hasta el número de particiones; agregar particiones sube ese techo.
 *
 * <h2>Sólo se marca lo que el broker confirmó</h2>
 *
 * Una unidad queda {@code dispatched_at} cuando su envío regresó bien. Si el envío falla, o el pod
 * muere a media página, la unidad sigue sin marca y la siguiente vuelta del monitor la vuelve a
 * mandar. Repetir un mensaje no hace daño: el consumidor toma la unidad con un CAS y el segundo
 * mensaje la encuentra tomada.
 */
@Service
public class CutoffDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CutoffDispatcher.class);

    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final CutoffUnitStore store;
    private final ClosingEventPublisher publisher;
    private final ClosingProperties properties;

    public CutoffDispatcher(CutoffUnitStore store, ClosingEventPublisher publisher, ClosingProperties properties) {
        this.store      = store;
        this.publisher  = publisher;
        this.properties = properties;
    }

    /**
     * Publica las unidades PENDING que nunca salieron o salieron hace demasiado.
     *
     * @return cuántas confirmó el broker
     */
    public int dispatch(UUID runId, LocalDate businessDate) {
        var cutoff = properties.getCutoff();
        Instant staleBefore = Instant.now().minus(cutoff.getRedispatchAfter());
        int pageSize = Math.max(cutoff.getDispatchPage(), 1);

        UUID after = null;
        int enviadas = 0;
        while (true) {
            List<CutoffUnitStore.Dispatchable> page = store.findDispatchable(runId, staleBefore, after, pageSize);
            if (page.isEmpty()) break;

            enviadas += sendPage(page, businessDate);
            after = page.get(page.size() - 1).unitId();
            if (page.size() < pageSize) break;
        }

        if (enviadas > 0) {
            log.info("Corte {}: {} cuentas despachadas al tópico de trabajo runId={}", businessDate, enviadas, runId);
        }
        return enviadas;
    }

    private int sendPage(List<CutoffUnitStore.Dispatchable> page, LocalDate businessDate) {
        var confirmadas = new ConcurrentLinkedQueue<UUID>();
        CompletableFuture<?>[] envios = page.stream()
                .map(u -> publisher.publishCutoffDue(u, businessDate)
                        .thenRun(() -> confirmadas.add(u.unitId()))
                        .exceptionally(ex -> {
                            log.warn("No salió la unidad {} de la cuenta {}: {} — se reintenta en la siguiente vuelta",
                                    u.unitId(), u.creditAccountId(), ex.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(envios).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Se marca lo que sí se confirmó; lo demás queda para la siguiente vuelta.
            log.warn("Página de despacho incompleta: {} de {} confirmadas", confirmadas.size(), page.size());
        }
        store.markDispatched(List.copyOf(confirmadas), Instant.now());
        return confirmadas.size();
    }
}
