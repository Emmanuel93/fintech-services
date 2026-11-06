package com.fintech.shared.kafka;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ¿Ya consumió este grupo todo lo que se había publicado en estos tópicos?
 *
 * <p>Es la compuerta entre dos pasos del cierre que viajan por tópicos distintos. Cargos avisa
 * «terminé el día» por {@code charges.day-accrued}, pero los intereses viajan por
 * {@code charges.charge-applied}: el aviso puede llegar a cartera antes que el último interés. Lo
 * mismo entre cartera y closing con los saldos. Esperar «un rato» no es una garantía; esto sí.
 *
 * <h2>Cómo</h2>
 *
 * Se toma una foto del final de cada partición <b>al empezar</b> y se espera a que el grupo confirme
 * offsets hasta ahí. Lo que se publique después no cuenta: con tráfico constante, perseguir el final
 * en movimiento no terminaría nunca. Funciona porque quien avisa publicó —y el broker confirmó—
 * todo lo suyo antes del aviso: la foto, tomada después de recibirlo, ya lo incluye.
 *
 * <p>Spring Kafka confirma el offset cuando el listener terminó con el mensaje (o lo dio por perdido
 * después de los reintentos). Que el grupo haya pasado la foto significa que todo lo anterior se
 * procesó; si algo se descartó, lo detectan las cifras de control de cada paso, no esto.
 */
public class ConsumerCatchUp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerCatchUp.class);
    private static final long TIMEOUT_SECONDS = 15;

    private final Supplier<Admin> factory;
    private volatile Admin admin;

    public ConsumerCatchUp(Supplier<Admin> factory) {
        this.factory = factory;
    }

    /** El resultado de esperar: si alcanzó la foto y cuántos mensajes le faltaban al final. */
    public record Result(boolean caughtUp, long pending, Duration waited) {}

    /**
     * Espera, hasta {@code maxWait}, a que el grupo alcance el final que los tópicos tienen ahora.
     * Con {@code maxWait} en cero sólo pregunta una vez.
     */
    public Result await(String groupId, Collection<String> topics, Duration maxWait, Duration every) {
        long inicio = System.nanoTime();
        Map<TopicPartition, Long> foto = endOffsets(topics);
        long faltan = pending(groupId, foto);
        long limite = inicio + maxWait.toNanos();
        while (faltan > 0 && System.nanoTime() < limite) {
            try {
                Thread.sleep(Math.max(10, every.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            faltan = pending(groupId, foto);
        }
        Duration espera = Duration.ofNanos(System.nanoTime() - inicio);
        if (faltan > 0) {
            log.info("El grupo {} no alcanzó {} en {} ms: le faltan {} mensajes", groupId, topics,
                    espera.toMillis(), faltan);
        }
        return new Result(faltan == 0, faltan, espera);
    }

    /** Cuántos mensajes le faltan al grupo para llegar a la foto. */
    long pending(String groupId, Map<TopicPartition, Long> foto) {
        if (foto.isEmpty()) return 0;
        Map<TopicPartition, OffsetAndMetadata> confirmados = get(
                admin().listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata());
        Map<TopicPartition, Long> inicios = null;
        long total = 0;
        for (var e : foto.entrySet()) {
            OffsetAndMetadata c = confirmados.get(e.getKey());
            long consumido;
            if (c != null) {
                consumido = c.offset();
            } else {
                // Sin offset confirmado: el grupo nunca leyó esa partición. Le falta todo lo retenido.
                if (inicios == null) inicios = offsets(foto.keySet(), OffsetSpec.earliest());
                consumido = inicios.getOrDefault(e.getKey(), 0L);
            }
            total += Math.max(0, e.getValue() - consumido);
        }
        return total;
    }

    /** El final de cada partición de los tópicos. Un tópico que no existe no tiene nada pendiente. */
    Map<TopicPartition, Long> endOffsets(Collection<String> topics) {
        Map<TopicPartition, OffsetSpec> pedir = new HashMap<>();
        Map<String, KafkaFuture<TopicDescription>> descripciones = admin().describeTopics(topics).topicNameValues();
        for (var e : descripciones.entrySet()) {
            try {
                TopicDescription d = get(e.getValue());
                d.partitions().forEach(p -> pedir.put(new TopicPartition(d.name(), p.partition()), OffsetSpec.latest()));
            } catch (IllegalStateException ex) {
                if (!(ex.getCause() instanceof UnknownTopicOrPartitionException)) throw ex;
                log.debug("El tópico {} no existe todavía: nada que esperar", e.getKey());
            }
        }
        if (pedir.isEmpty()) return Map.of();
        return offsets(pedir.keySet(), OffsetSpec.latest());
    }

    private Map<TopicPartition, Long> offsets(Collection<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> pedir = new HashMap<>();
        partitions.forEach(tp -> pedir.put(tp, spec));
        Map<TopicPartition, Long> out = new HashMap<>();
        get(admin().listOffsets(pedir).all()).forEach((tp, info) -> out.put(tp, info.offset()));
        return out;
    }

    private Admin admin() {
        Admin a = admin;
        if (a == null) {
            synchronized (this) {
                if (admin == null) admin = factory.get();
                a = admin;
            }
        }
        return a;
    }

    private static <T> T get(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al consultar offsets", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("No se pudieron consultar los offsets: " + e.getCause().getMessage(),
                    e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("El broker no respondió la consulta de offsets en "
                                            + TIMEOUT_SECONDS + " s", e);
        }
    }

    @Override
    public void close() {
        Admin a = admin;
        if (a != null) a.close(Duration.ofSeconds(5));
    }
}
