package com.fintech.compliance;

import com.fintech.shared.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El productor publica JSON.
 *
 * <p>Parece trivial y costó la primera corrida en Docker: el productor quedó con el
 * StringSerializer por defecto, cada veredicto fallaba al serializarse, el publicador lo registraba
 * sin relanzar, y las pruebas —que no tienen Kafka— seguían en verde. Esta prueba fija la
 * configuración que hace que un dictamen llegue río abajo.
 */
@SpringBootTest
class KafkaProducerConfigIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired ProducerFactory<?, ?> producerFactory;

    @Test
    @DisplayName("los veredictos salen como JSON y sin cabeceras de tipo de este servicio")
    void verdictsArePublishedAsJson() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertThat(config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(JsonSerializer.class);
        assertThat(String.valueOf(config.get(JsonSerializer.ADD_TYPE_INFO_HEADERS))).isEqualTo("false");
    }

    @Test
    @DisplayName("y el mapa que arma el publicador se serializa sin error")
    void theVerdictPayloadSerializes() {
        try (var serializer = new JsonSerializer<Object>()) {
            byte[] bytes = serializer.serialize("compliance.verification-completed",
                    Map.of("prospectId", "p", "identityStatus", "VERIFIED"));
            assertThat(new String(bytes)).contains("\"identityStatus\":\"VERIFIED\"");
        }
    }
}
