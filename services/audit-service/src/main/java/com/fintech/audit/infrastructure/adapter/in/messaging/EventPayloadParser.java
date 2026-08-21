package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

class EventPayloadParser {

    private static final Logger log = LoggerFactory.getLogger(EventPayloadParser.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();

    private EventPayloadParser() {}

    static Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not parse event payload: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    static String field(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) return val.toString();
        }
        return null;
    }
}
