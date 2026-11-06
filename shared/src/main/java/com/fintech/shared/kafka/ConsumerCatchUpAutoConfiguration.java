package com.fintech.shared.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * La compuerta de consumo ({@link ConsumerCatchUp}) en todo servicio con Kafka. Usa la misma
 * configuración que el {@code KafkaAdmin} de Spring Boot; el cliente se crea la primera vez que
 * alguien pregunta, así que no cuesta nada a quien no la usa.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({Admin.class, KafkaAdmin.class})
@ConditionalOnBean(KafkaAdmin.class)
public class ConsumerCatchUpAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ConsumerCatchUp consumerCatchUp(KafkaAdmin kafkaAdmin) {
        return new ConsumerCatchUp(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
    }

    /** Whether the service is at rest, for a deterministic run (see {@link TestSupportQuiescenceController}). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
    @Import(TestSupportQuiescenceController.class)
    static class TestSupportQuiescenceConfiguration {
    }
}
