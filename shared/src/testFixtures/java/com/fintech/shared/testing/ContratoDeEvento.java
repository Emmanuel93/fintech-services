package com.fintech.shared.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que lo que un productor publica lo entiende el consumidor real.
 *
 * <p><b>Por qué existe.</b> En este monorepo cada consumidor <b>re-declara a mano</b> el payload del
 * productor en su propio paquete ACL. Es una decisión de diseño defendible —evita acoplar módulos—
 * pero tiene un costo que se pagó caro: <b>un cambio de contrato entre dos servicios no rompe
 * ninguna prueba</b>. Así sobrevivieron tres listeners que escuchan topics que nadie publica y
 * cuarenta y dos topics que nadie consume.
 *
 * <p>Esta clase cierra ese hueco sin acoplar los módulos: serializa el evento <b>del productor</b>,
 * lo deserializa con el payload <b>del consumidor</b> —usando el mismo deserializador que usa el
 * contenedor de Kafka— y afirma que los campos que el consumidor lee llegaron con valor.
 *
 * <pre>{@code
 * ContratoDeEvento.entre(eventoDelProductor, PayloadDelConsumidor.class)
 *         .exige("creditAccountId", PayloadDelConsumidor::creditAccountId)
 *         .exige("amount",          PayloadDelConsumidor::amount)
 *         .verificar();
 * }</pre>
 */
public final class ContratoDeEvento<C> {

    private final Object eventoProductor;
    private final Class<C> tipoConsumidor;
    private final java.util.Map<String, Function<C, Object>> exigidos = new java.util.LinkedHashMap<>();

    private ContratoDeEvento(Object eventoProductor, Class<C> tipoConsumidor) {
        this.eventoProductor = eventoProductor;
        this.tipoConsumidor  = tipoConsumidor;
    }

    public static <C> ContratoDeEvento<C> entre(Object eventoProductor, Class<C> tipoConsumidor) {
        return new ContratoDeEvento<>(eventoProductor, tipoConsumidor);
    }

    /** Un campo que el consumidor lee y que, por tanto, el productor tiene que mandar con valor. */
    public ContratoDeEvento<C> exige(String campo, Function<C, Object> extractor) {
        exigidos.put(campo, extractor);
        return this;
    }

    /**
     * Serializa como el productor, deserializa como el consumidor, y comprueba los campos exigidos.
     *
     * @return el payload del consumidor, por si la prueba quiere afirmar algo más
     */
    public C verificar() {
        String json;
        try {
            json = new ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(eventoProductor);
        } catch (Exception e) {
            throw new AssertionError("El evento del productor no se pudo serializar: " + e.getMessage(), e);
        }

        C payload;
        // El MISMO deserializador que construye el contenedor de Kafka. Usar un ObjectMapper suelto
        // aquí probaría otra cosa: la tolerancia a campos desconocidos depende de cuál se construye.
        try (JsonDeserializer<C> d = new JsonDeserializer<>()) {
            d.configure(Map.of(
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName(),
                    JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*",
                    JsonDeserializer.VALUE_DEFAULT_TYPE, tipoConsumidor.getName(),
                    JsonDeserializer.USE_TYPE_INFO_HEADERS, false), false);
            payload = d.deserialize("contrato", json.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(payload)
                .as("El consumidor %s no pudo deserializar lo que publica %s.%nJSON: %s",
                        tipoConsumidor.getSimpleName(), eventoProductor.getClass().getSimpleName(), json)
                .isNotNull();

        exigidos.forEach((campo, extractor) ->
                assertThat(extractor.apply(payload))
                        .as("El consumidor %s lee '%s' y el productor %s no lo mandó con valor",
                                tipoConsumidor.getSimpleName(), campo,
                                eventoProductor.getClass().getSimpleName())
                        .isNotNull());

        return payload;
    }
}
