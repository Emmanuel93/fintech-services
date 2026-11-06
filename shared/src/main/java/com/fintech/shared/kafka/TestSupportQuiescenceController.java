package com.fintech.shared.kafka;

import com.fintech.shared.scheduling.PendingWork;
import com.fintech.shared.simulation.SimulatorGuard;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Whether this service consumed everything published to the topics it listens to: {@code GET
 * /internal/test-support/quiescence}.
 *
 * <p>A deterministic run moves the clock to the next day only once the system is at rest. It used to decide that from
 * outside, with domain counts and two equal readings 0.3 s apart: nothing waited for what risk, collections or the
 * commission run publish. Each service now says it of itself, with the same gate the close uses between its steps
 * ({@link ConsumerCatchUp}): the end of every partition now, against what each of its groups committed.
 *
 * <p>Consumed is not done: what a service keeps in a table for a poller of its own (an order to dispatch, a message to
 * relay) is invisible to the lag. Each such queue declares itself as {@link PendingWork}, and the service is at rest
 * only when its groups caught up and its queues hold nothing due.
 *
 * <p>Only published with {@code fintech.test-support.enabled=true}, and never in production.
 */
@RestController
@RequestMapping("/internal/test-support/quiescence")
public class TestSupportQuiescenceController {

    private final ObjectProvider<KafkaListenerEndpointRegistry> registry;
    private final ObjectProvider<PendingWork> work;
    private final ConsumerCatchUp catchUp;
    private final String service;

    public TestSupportQuiescenceController(Environment environment, ObjectProvider<KafkaListenerEndpointRegistry> registry,
                                           ObjectProvider<PendingWork> work, ConsumerCatchUp catchUp,
                                           @Value("${spring.application.name:unknown}") String service) {
        SimulatorGuard.requireNonProduction(environment, "reposo de los consumidores", "TEST_SUPPORT_ENABLED=false");
        this.registry = registry;
        this.work = work;
        this.catchUp = catchUp;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> view() {
        Map<String, Set<String>> topicsByGroup = new TreeMap<>();
        KafkaListenerEndpointRegistry listeners = registry.getIfAvailable();
        if (listeners != null) {
            for (MessageListenerContainer c : listeners.getListenerContainers()) {
                if (!c.isRunning()) continue;   // a stopped listener consumes nothing: waiting on it would never end
                String group = c.getGroupId();
                String[] topics = c.getContainerProperties().getTopics();
                if (group == null || topics == null) continue;   // a pattern subscription: not a fixed set to wait on
                topicsByGroup.computeIfAbsent(group, g -> new TreeSet<>()).addAll(Arrays.asList(topics));
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Set<String>> e : topicsByGroup.entrySet()) {
            Map<TopicPartition, Long> ends = catchUp.endOffsets(e.getValue());
            long pending = catchUp.pending(e.getKey(), ends);
            total += pending;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("groupId", e.getKey());
            g.put("topics", e.getValue());
            g.put("pending", pending);
            groups.add(g);
        }
        List<Map<String, Object>> queues = new ArrayList<>();
        for (PendingWork w : work.orderedStream().toList()) {
            long pending = w.pending();
            total += pending;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("name", w.name());
            q.put("pending", pending);
            queues.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("quiet", total == 0);
        out.put("pending", total);
        out.put("groups", groups);
        out.put("work", queues);
        return out;
    }
}
