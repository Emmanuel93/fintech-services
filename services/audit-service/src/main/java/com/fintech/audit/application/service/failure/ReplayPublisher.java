package com.fintech.audit.application.service.failure;

import java.util.Map;

/** Sends a failed message back to its original topic, as it was, so its consumer handles it again. */
public interface ReplayPublisher {

    void republish(String topic, String key, String payload, Map<String, byte[]> headers);
}
