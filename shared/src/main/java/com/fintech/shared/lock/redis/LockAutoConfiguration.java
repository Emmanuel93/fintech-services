package com.fintech.shared.lock.redis;

import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockProperties;
import com.fintech.shared.lock.redisson.RedissonDistributedLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Publica el candado, eligiendo implementación por {@code fintech.lock.provider}.
 *
 * <p>Las dos viven detrás del mismo puerto, así que cambiar de una a otra es una propiedad y no una
 * migración. Cada una es su propia autoconfiguración —registrada en {@code AutoConfiguration.imports}—
 * porque {@code @ConditionalOnClass} tiene que poder descartarla sin cargar la clase que menciona.
 *
 * <p><b>El {@code after} no es decorativo.</b> {@code @ConditionalOnBean} depende del orden de
 * evaluación: si esta configuración se procesa antes que la del proveedor, el cliente todavía no
 * existe, la condición falla <em>en silencio</em> y el candado simplemente no aparece. Es un fallo
 * que no se ve al compilar y se manifiesta como un bean que falta al arrancar.
 */
public final class LockAutoConfiguration {

    private LockAutoConfiguration() {}

    /** Predeterminado: Redisson. Arrendamiento con watchdog. */
    @AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnProperty(name = "fintech.lock.enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(LockProperties.class)
    public static class RedissonLockConfiguration {

        @Bean
        @ConditionalOnBean(RedissonClient.class)
        @ConditionalOnMissingBean(DistributedLock.class)
        @ConditionalOnProperty(name = "fintech.lock.provider", havingValue = "redisson", matchIfMissing = true)
        public DistributedLock redissonDistributedLock(RedissonClient redisson, LockProperties properties) {
            return new RedissonDistributedLock(redisson, properties, Clock.systemUTC());
        }
    }

    /** Alternativa sin dependencias nuevas, para un servicio que no quiera traer Redisson. */
    @AutoConfiguration(after = RedisAutoConfiguration.class)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(name = "fintech.lock.enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(LockProperties.class)
    public static class RedisTemplateLockConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(DistributedLock.class)
        @ConditionalOnProperty(name = "fintech.lock.provider", havingValue = "redis-template")
        public DistributedLock redisTemplateDistributedLock(StringRedisTemplate redis) {
            return new RedisDistributedLock(redis, Clock.systemUTC());
        }
    }
}
