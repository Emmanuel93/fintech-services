package com.fintech.accounting.infrastructure.config;

import com.fintech.shared.trace.kafka.DeadLetters;
import com.fintech.accounting.infrastructure.adapter.in.messaging.BalanceUpdatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CustomerBalanceMovedListener;
import com.fintech.accounting.infrastructure.adapter.in.messaging.DisbursementAcceptedListener;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionAccruedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionLotPayoutListener;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CommissionReversedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CreditAccountCancelledPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.RecoveryPaymentAppliedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.CashMovementRecordedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.FundingMovementPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.PortfolioAccountDayClosedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.PortfolioDayClosedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.RiskAssessmentUpdatedPayload;
import com.fintech.accounting.infrastructure.adapter.in.messaging.WithdrawalCompletedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@Configuration
public class KafkaConfig {

    /**
     * Every listener retries (1 s, 2 s, 4 s, 8 s, 16 s) and then sends the message to {@code <topic>.DLT}, where the
     * failure registry keeps it for replay. Without it a failing message was retried nine times at once and dropped
     * with a log line: a balance fact that arrived before its account's birth was lost, or mis-booked.
     */
    @Autowired
    private DeadLetters deadLetters;

    private <T> ConsumerFactory<String, T> jsonConsumerFactory(KafkaProperties properties, Class<T> type) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>
    balanceUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, BalanceUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, BalanceUpdatedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>
    creditAccountActivatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountActivatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountActivatedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CreditAccountCancelledPayload>
    creditAccountCancelledListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CreditAccountCancelledPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CreditAccountCancelledPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RiskAssessmentUpdatedPayload>
    riskAssessmentUpdatedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RiskAssessmentUpdatedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, RiskAssessmentUpdatedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CashMovementRecordedPayload>
    cashMovementRecordedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CashMovementRecordedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CashMovementRecordedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CustomerBalanceMovedListener.CustomerBalanceMovedPayload>
    customerBalanceMovedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CustomerBalanceMovedListener.CustomerBalanceMovedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CustomerBalanceMovedListener.CustomerBalanceMovedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DisbursementAcceptedListener.DisbursementAcceptedPayload>
    disbursementAcceptedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, DisbursementAcceptedListener.DisbursementAcceptedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, DisbursementAcceptedListener.DisbursementAcceptedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FundingMovementPayload>
    fundingMovementListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, FundingMovementPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, FundingMovementPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PortfolioDayClosedPayload>
    portfolioDayClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PortfolioDayClosedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PortfolioDayClosedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PortfolioAccountDayClosedPayload>
    portfolioAccountDayClosedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, PortfolioAccountDayClosedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, PortfolioAccountDayClosedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RecoveryPaymentAppliedPayload>
    recoveryPaymentAppliedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, RecoveryPaymentAppliedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, RecoveryPaymentAppliedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, WithdrawalCompletedPayload>
    withdrawalCompletedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, WithdrawalCompletedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, WithdrawalCompletedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionAccruedPayload>
    commissionAccruedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionAccruedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionAccruedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionReversedPayload>
    commissionReversedListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionReversedPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionReversedPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommissionLotPayoutListener.LotPayoutPayload>
    commissionLotPayoutListenerContainerFactory(KafkaProperties properties) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, CommissionLotPayoutListener.LotPayoutPayload>();
        factory.setConsumerFactory(jsonConsumerFactory(properties, CommissionLotPayoutListener.LotPayoutPayload.class));
        factory.setCommonErrorHandler(deadLetters.errorHandler());
        return factory;
    }
}
