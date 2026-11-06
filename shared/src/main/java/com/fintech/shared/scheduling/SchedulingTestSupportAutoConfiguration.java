package com.fintech.shared.scheduling;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Publishes {@link TestSupportSchedulingController} in every web service with test support enabled, with or without
 * ShedLock: a deterministic run asks each one whether its business crons are paused before it starts.
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
@Import(TestSupportSchedulingController.class)
public class SchedulingTestSupportAutoConfiguration {
}
