package com.fintech.shared.kafka;

import com.fintech.shared.scheduling.PendingWork;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSupportQuiescenceControllerTest {

    private static MessageListenerContainer listening(String group, String... topics) {
        MessageListenerContainer c = mock(MessageListenerContainer.class);
        when(c.isRunning()).thenReturn(true);
        when(c.getGroupId()).thenReturn(group);
        when(c.getContainerProperties()).thenReturn(new ContainerProperties(topics));
        return c;
    }

    private static PendingWork queue(String name, AtomicLong due) {
        return new PendingWork() {
            @Override public String name() { return name; }
            @Override public long pending() { return due.get(); }
        };
    }

    private static TestSupportQuiescenceController controller(MockEnvironment env, ConsumerCatchUp catchUp,
                                                              MessageListenerContainer... containers) {
        return controller(env, catchUp, List.of(), containers);
    }

    @SuppressWarnings("unchecked")
    private static TestSupportQuiescenceController controller(MockEnvironment env, ConsumerCatchUp catchUp,
                                                              List<PendingWork> queues,
                                                              MessageListenerContainer... containers) {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(List.of(containers));
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        ObjectProvider<PendingWork> work = mock(ObjectProvider.class);
        when(work.orderedStream()).thenAnswer(i -> queues.stream());
        return new TestSupportQuiescenceController(env, provider, work, catchUp, "credit-portfolio-service");
    }

    @Test
    @DisplayName("en reposo sólo cuando cada grupo de sus listeners consumió hasta el final de sus tópicos")
    void quietOnlyWhenEveryGroupCaughtUp() {
        ConsumerCatchUp catchUp = mock(ConsumerCatchUp.class);
        when(catchUp.endOffsets(anyCollection())).thenReturn(Map.of());
        when(catchUp.pending(eq("cartera"), any())).thenReturn(0L);
        when(catchUp.pending(eq("cartera-dlt"), any())).thenReturn(3L);
        MockEnvironment local = new MockEnvironment().withProperty("fintech.environment", "local");

        Map<String, Object> busy = controller(local, catchUp,
                listening("cartera", "charges.charge-applied", "payments.payment-applied"),
                listening("cartera", "charges.day-accrued"),
                listening("cartera-dlt", "charges.charge-applied.DLT")).view();

        assertThat(busy).containsEntry("quiet", false).containsEntry("pending", 3L);
        assertThat((List<?>) busy.get("groups")).hasSize(2);

        when(catchUp.pending(eq("cartera-dlt"), any())).thenReturn(0L);
        assertThat(controller(local, catchUp, listening("cartera", "charges.charge-applied"),
                listening("cartera-dlt", "charges.charge-applied.DLT")).view()).containsEntry("quiet", true);
    }

    @Test
    @DisplayName("consumido no es hecho: una cola con trabajo vencido lo saca del reposo aunque Kafka esté al día")
    void dueWorkInATableKeepsItBusy() {
        ConsumerCatchUp catchUp = mock(ConsumerCatchUp.class);
        when(catchUp.endOffsets(anyCollection())).thenReturn(Map.of());
        when(catchUp.pending(any(), any())).thenReturn(0L);
        MockEnvironment local = new MockEnvironment().withProperty("fintech.environment", "local");
        AtomicLong due = new AtomicLong(2);
        TestSupportQuiescenceController c = controller(local, catchUp, List.of(queue("disbursement-dispatch", due)),
                listening("desembolsos", "commission.lot-payout-requested"));

        Map<String, Object> busy = c.view();

        assertThat(busy).containsEntry("quiet", false).containsEntry("pending", 2L);
        assertThat((List<?>) busy.get("work")).singleElement()
                .isEqualTo(Map.of("name", "disbursement-dispatch", "pending", 2L));

        due.set(0);
        assertThat(c.view()).containsEntry("quiet", true).containsEntry("pending", 0L);
    }

    @Test
    @DisplayName("nunca en producción")
    void neverInProduction() {
        MockEnvironment prod = new MockEnvironment().withProperty("fintech.environment", "prod");
        assertThatThrownBy(() -> controller(prod, mock(ConsumerCatchUp.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
