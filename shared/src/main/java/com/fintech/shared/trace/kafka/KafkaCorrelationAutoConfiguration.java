package com.fintech.shared.trace.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * Correlation id and failure handling on every Kafka listener and template of the service, including the
 * listener factories each service builds by hand (23 of them): a post-processor stamps outgoing messages, puts
 * each incoming message's id in the log context, and gives the factories that have no error handler of their own
 * the shared one (retries, then the dead letter). A factory that sets its own handler keeps it; it can build it
 * from {@link DeadLetters#errorHandler(org.springframework.util.backoff.BackOff)} to end in the dead letter too.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, AbstractKafkaListenerContainerFactory.class})
public class KafkaCorrelationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeadLetters deadLetters(KafkaProperties kafkaProperties, Environment environment) {
        return new DeadLetters(kafkaProperties, environment.getProperty("spring.application.name", "service"),
                environment.getProperty("fintech.kafka.failures.retries", Integer.class, 5),
                environment.getProperty("fintech.kafka.failures.initial-interval-ms", Long.class, 1_000L));
    }

    @Bean
    public static BeanPostProcessor kafkaCorrelationPostProcessor(ObjectProvider<DeadLetters> deadLetters) {
        return new KafkaCorrelationPostProcessor(deadLetters);
    }

    static final class KafkaCorrelationPostProcessor implements BeanPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(KafkaCorrelationPostProcessor.class);
        private static final Field ERROR_HANDLER =
                ReflectionUtils.findField(AbstractKafkaListenerContainerFactory.class, "commonErrorHandler");

        private final ObjectProvider<DeadLetters> deadLetters;

        KafkaCorrelationPostProcessor(ObjectProvider<DeadLetters> deadLetters) {
            this.deadLetters = deadLetters;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof KafkaTemplate template) {
                template.setProducerInterceptor(new CorrelationProducerInterceptor<>());
            } else if (bean instanceof AbstractKafkaListenerContainerFactory factory) {
                CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor<>();
                factory.setRecordInterceptor(interceptor);
                factory.setBatchInterceptor(interceptor);
                if (ERROR_HANDLER != null) {
                    ReflectionUtils.makeAccessible(ERROR_HANDLER);
                    Object own = ReflectionUtils.getField(ERROR_HANDLER, factory);
                    if (own == null) {
                        CommonErrorHandler shared = deadLetters.getObject().errorHandler();
                        factory.setCommonErrorHandler(shared);
                        log.info("Fábrica de listeners {}: reintentos con espera y mensaje fallido a <topic>.DLT", beanName);
                    }
                }
            }
            return bean;
        }
    }
}
