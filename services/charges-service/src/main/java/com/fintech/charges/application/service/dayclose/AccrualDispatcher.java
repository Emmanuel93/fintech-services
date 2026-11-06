package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
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
 * Manda al tópico de trabajo ({@code charges.accrual-due}) las cuentas pendientes de un día.
 *
 * <p>Kafka reparte las particiones entre los pods del grupo y cada cuenta llega a uno solo: agregar pods
 * acelera hasta el número de particiones. Sólo se marca lo que el broker confirmó; lo demás lo vuelve a
 * mandar la siguiente vuelta del monitor. Un mensaje repetido no hace daño: la cuenta se toma con CAS.
 */
@Service
public class AccrualDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AccrualDispatcher.class);
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final AccrualUnitStore units;
    private final ChargeEventPublisher publisher;
    private final UnitSettings settings;

    public AccrualDispatcher(AccrualUnitStore units, ChargeEventPublisher publisher, UnitSettings settings) {
        this.units     = units;
        this.publisher = publisher;
        this.settings  = settings;
    }

    /** @return cuántas cuentas confirmó el broker */
    public int dispatch(LocalDate businessDate) {
        Instant staleBefore = Instant.now().minus(settings.redispatchAfter());
        int pagina = Math.max(settings.dispatchPage(), 1);
        UUID after = null;
        int enviadas = 0;
        while (true) {
            List<UUID> cuentas = units.dispatchable(businessDate, staleBefore, after, pagina);
            if (cuentas.isEmpty()) break;
            enviadas += sendPage(businessDate, cuentas);
            after = cuentas.get(cuentas.size() - 1);
            if (cuentas.size() < pagina) break;
        }
        if (enviadas > 0) {
            log.info("Cierre de cargos del {}: {} cuentas despachadas al tópico de trabajo", businessDate, enviadas);
        }
        return enviadas;
    }

    private int sendPage(LocalDate businessDate, List<UUID> cuentas) {
        var confirmadas = new ConcurrentLinkedQueue<UUID>();
        CompletableFuture<?>[] envios = cuentas.stream()
                .map(cuenta -> publisher.publishAccrualDue(businessDate, cuenta)
                        .thenRun(() -> confirmadas.add(cuenta))
                        .exceptionally(ex -> {
                            log.warn("No salió la cuenta {} del cierre del {}: {} — se reintenta en la siguiente vuelta",
                                    cuenta, businessDate, ex.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(envios).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Página de despacho incompleta: {} de {} confirmadas", confirmadas.size(), cuentas.size());
        }
        units.markDispatched(businessDate, List.copyOf(confirmadas), Instant.now());
        return confirmadas.size();
    }
}
