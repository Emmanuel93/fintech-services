package com.fintech.wallet.infrastructure;

import com.fintech.wallet.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de consumo: que un productor añada un campo <b>no</b> puede romper a este servicio.
 *
 * <p>Los payloads de wallet no llevan {@code @JsonIgnoreProperties(ignoreUnknown = true)}, a
 * diferencia de los de los otros diez consumidores. Esta prueba fija si eso importa o no — y lo
 * hace ejercitando el <b>mismo deserializador que usa el contenedor</b>, no un ObjectMapper
 * cualquiera, porque la tolerancia depende de cuál se construye y cómo.
 */
class ContratoDeEventosTest {

    /** Construido igual que en {@code KafkaConfig.jsonConsumerFactory}. */
    private <T> JsonDeserializer<T> deserializadorComoEnProduccion(Class<T> type) {
        JsonDeserializer<T> d = new JsonDeserializer<>();
        d.configure(Map.of(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class.getName(),
                JsonDeserializer.TRUSTED_PACKAGES, "com.fintech.*",
                JsonDeserializer.VALUE_DEFAULT_TYPE, type.getName(),
                JsonDeserializer.USE_TYPE_INFO_HEADERS, false), false);
        return d;
    }

    @Test
    @DisplayName("un evento con campos que el payload no declara se deserializa igual")
    void toleraCamposNuevos() {
        // Los tres que el evento real YA publica y este payload no declara, más dos hipotéticos
        // que el cierre necesitaría para derivar su calendario de corte.
        String json = """
                {
                  "eventId": "e-1",
                  "creditAccountId": "11111111-1111-1111-1111-111111111111",
                  "contractId": "22222222-2222-2222-2222-222222222222",
                  "obligorPartyId": "33333333-3333-3333-3333-333333333333",
                  "productType": "PERSONAL_LOAN",
                  "productBehavior": "INSTALLMENT",
                  "nominalRate": 0.32,
                  "moratoriumRate": 0.48,
                  "openingFeeRate": 0.00,
                  "principalBalance": 20000.00,
                  "creditLimit": null,
                  "riskTier": "A",
                  "activatedAt": "2026-08-24T10:00:00Z",
                  "occurredOn": "2026-08-24T10:00:00Z",
                  "promoterCode": "PROM-1",
                  "originUnitCode": "SUC-001",
                  "disbursementInstruction": { "dispositionId": "44444444-4444-4444-4444-444444444444" },
                  "paymentFrequency": "MONTHLY",
                  "termPeriods": 12
                }
                """;

        try (JsonDeserializer<CreditAccountActivatedPayload> d =
                     deserializadorComoEnProduccion(CreditAccountActivatedPayload.class)) {

            CreditAccountActivatedPayload p =
                    d.deserialize("credit-portfolio.credit-account-activated",
                            json.getBytes(StandardCharsets.UTF_8));

            assertThat(p).isNotNull();
            assertThat(p.productType()).isEqualTo("PERSONAL_LOAN");
            assertThat(p.principalBalance()).isEqualByComparingTo("20000.00");
        }
    }
}
