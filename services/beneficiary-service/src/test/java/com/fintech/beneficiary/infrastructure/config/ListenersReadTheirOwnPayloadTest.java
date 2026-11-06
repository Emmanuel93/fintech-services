package com.fintech.beneficiary.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each factory pins the type it deserializes into ({@code VALUE_DEFAULT_TYPE}, type headers off): a listener of any
 * other payload reads the wrong class and every message goes to the DLT. D63's {@code disposition-failed} listener
 * shipped without naming its factory and did exactly that, which only the E2E caught. This test checks that wiring for
 * every listener of the service, the ones to come included.
 */
class ListenersReadTheirOwnPayloadTest {

    private static final String MESSAGING = "com.fintech.beneficiary.infrastructure.adapter.in.messaging";
    private static final String DEFAULT_FACTORY = "kafkaListenerContainerFactory";

    @Test
    @DisplayName("cada consumidor nombra la fábrica que deserializa su propia carga")
    void everyListenerReadsItsOwnPayload() throws Exception {
        Map<String, Class<?>> pinned = new HashMap<>();
        for (Method m : KafkaConsumerConfig.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Bean.class) && m.getGenericReturnType() instanceof ParameterizedType t) {
                pinned.put(m.getName(), (Class<?>) t.getActualTypeArguments()[1]);
            }
        }

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        int listeners = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents(MESSAGING)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                KafkaListener listener = m.getAnnotation(KafkaListener.class);
                if (listener == null) {
                    continue;
                }
                String factory = listener.containerFactory().isEmpty() ? DEFAULT_FACTORY : listener.containerFactory();
                String where = type.getSimpleName() + "." + m.getName();
                assertThat(pinned).as("%s usa la fábrica %s, que no existe", where, factory).containsKey(factory);
                assertThat(pinned.get(factory)).as("la fábrica %s deserializa la carga de %s", factory, where)
                        .isEqualTo(m.getParameterTypes()[0]);
                listeners++;
            }
        }
        assertThat(listeners).as("consumidores revisados").isGreaterThanOrEqualTo(3);
    }
}
