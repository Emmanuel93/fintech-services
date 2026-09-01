package com.fintech.notifications.infrastructure;

import com.fintech.notifications.infrastructure.adapter.in.messaging.ExecutiveAssignedPayload;
import com.fintech.shared.testing.ContratoDeEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato de {@code party.executive-assigned}, entre quien lo publica y quien lo lee.
 *
 * <p>Notifications consume hechos de otros dominios <b>redeclarando</b> sus payloads: no importa ni
 * una clase ajena, y por eso puede reaccionar a lo que pasa sin atarse al código de nadie. Lo que
 * sí queda atado es el <b>esquema</b>, y ahí está el riesgo real de este diseño: si party renombra
 * un campo, este servicio deja de poblarlo <b>en silencio</b> — el aviso sale, con un hueco donde
 * iba el nombre, y nadie se entera.
 *
 * <p>Es la misma clase de defecto que costó una tarde con {@code DispositionAuthorizedEvent}: el
 * beneficiario viajaba anidado y el consumidor lo esperaba plano, así que los tres campos que
 * deciden adónde va el dinero llegaban nulos. Lo cazó una prueba de contrato antes de llegar a
 * ningún ambiente, y por eso hay una aquí.
 */
class ContratoAsignacionDeEjecutivoTest {

    /** Copia fiel de lo que publica {@code KafkaPartyEventPublisher}. */
    record HechoDeParty(UUID partyId, UUID executiveId, String executiveName,
                        String clientName, Instant occurredOn) {}

    @Test
    @DisplayName("Lo que party publica llega con los cuatro campos que el aviso necesita")
    void el_hecho_llega_completo() {
        HechoDeParty publicado = new HechoDeParty(
                UUID.randomUUID(), UUID.randomUUID(), "Ximena Herrera", "Ana Ruiz", Instant.now());

        ExecutiveAssignedPayload leido = ContratoDeEvento
                .entre(publicado, ExecutiveAssignedPayload.class)
                // El destinatario: sin él no hay a quién avisarle.
                .exige("executiveId", ExecutiveAssignedPayload::executiveId)
                // El texto del aviso dice quién es el cliente; sin esto queda «un cliente».
                .exige("clientName", ExecutiveAssignedPayload::clientName)
                // La deduplicación se arma con partyId + executiveId.
                .exige("partyId", ExecutiveAssignedPayload::partyId)
                .verificar();

        assertThat(leido.executiveId()).isEqualTo(publicado.executiveId());
        assertThat(leido.clientName()).isEqualTo("Ana Ruiz");
    }

    @Test
    @DisplayName("Un campo que party añada después no rompe al consumidor")
    void los_campos_nuevos_no_rompen() {
        // Al revés que el renombrado: añadir es seguro, y tiene que seguir siéndolo para que party
        // pueda enriquecer su hecho sin coordinar un despliegue con cada consumidor.
        record HechoAmpliado(UUID partyId, UUID executiveId, String executiveName,
                             String clientName, Instant occurredOn, String sucursal) {}

        ExecutiveAssignedPayload leido = ContratoDeEvento
                .entre(new HechoAmpliado(UUID.randomUUID(), UUID.randomUUID(), "Ximena",
                                "Ana Ruiz", Instant.now(), "SUC-001"),
                        ExecutiveAssignedPayload.class)
                .exige("clientName", ExecutiveAssignedPayload::clientName)
                .verificar();

        assertThat(leido.clientName()).isEqualTo("Ana Ruiz");
    }
}
