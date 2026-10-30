package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ComplianceClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * La mesa de cumplimiento en el BFF: que componga la ficha sin que una fuente caída la tumbe, y que
 * cada dictamen salga firmado por quien está en sesión — nunca por lo que diga el navegador.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock ComplianceClient compliance;
    @Mock OriginationClient origination;
    @Mock StaffIdentityResolver staffIdentities;

    ComplianceController controller;

    @BeforeEach
    void setUp() { controller = new ComplianceController(compliance, origination, staffIdentities); }

    @AfterEach
    void sinSesion() { SecurityContextHolder.clearContext(); }

    private static void sesionDe(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static Map<String, Object> verificacion(UUID id, UUID prospectId) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("verificationId", id.toString());
        v.put("prospectId", prospectId.toString());
        v.put("identityStatus", "IN_REVIEW");
        v.put("screeningStatus", "CLEARED");
        return v;
    }

    @Test
    @DisplayName("la cola la resuelve compliance: el BFF pasa los filtros tal cual")
    void theQueueIsResolvedByTheOwner() {
        given(compliance.search(List.of("IN_REVIEW"), null, "rosa", 0, 50)).willReturn(Map.of("content", List.of()));

        controller.queue(List.of("IN_REVIEW"), null, "rosa", 0, 50);

        verify(compliance).search(List.of("IN_REVIEW"), null, "rosa", 0, 50);
        verifyNoInteractions(origination);
    }

    @Test
    @DisplayName("la ficha suma veredicto, persona, documentos y bitácora — una llamada por fuente")
    void theDetailComposesOneCallPerSource() {
        UUID id = UUID.randomUUID(), prospectId = UUID.randomUUID();
        given(compliance.verification(id)).willReturn(verificacion(id, prospectId));
        given(origination.documents(prospectId)).willReturn(List.of(
                Map.of("type", "INE_FRONT", "name", "ine.jpg", "contentType", "image/jpeg", "size", 1234)));
        given(compliance.checks(prospectId, 20)).willReturn(Map.of("checks", List.of(Map.of("result", "CLEARED"))));

        Map<String, Object> body = controller.detail(id);

        assertThat(body).containsKeys("verification", "prospect", "documents", "checks");
        @SuppressWarnings("unchecked")
        var docs = (List<Map<String, Object>>) body.get("documents");
        assertThat(docs).singleElement().satisfies(d -> {
            assertThat(d.get("documentType")).isEqualTo("INE_FRONT");
            assertThat(d.get("sizeBytes")).isEqualTo(1234);
        });
        verify(origination, times(1)).documents(prospectId);
        verify(origination, times(1)).getProspect(prospectId);
    }

    @Test
    @DisplayName("si origination no contesta, la ficha sigue: sin INE, pero con el veredicto para dictaminar")
    void aFailingOriginationDegradesOnlyItsBlocks() {
        UUID id = UUID.randomUUID(), prospectId = UUID.randomUUID();
        given(compliance.verification(id)).willReturn(verificacion(id, prospectId));
        given(origination.getProspect(prospectId)).willThrow(new IllegalStateException("caído"));
        given(origination.documents(prospectId)).willThrow(new IllegalStateException("caído"));
        given(compliance.checks(any(), anyInt())).willReturn(Map.of());

        Map<String, Object> body = controller.detail(id);

        assertThat(body.get("verification")).isNotNull();
        assertThat(body.get("prospect")).isNull();
        assertThat(body.get("documents")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("el dictamen se firma con el correo del staff en sesión")
    void theVerdictIsSignedWithTheSessionEmail() {
        sesionDe("staff-1");
        UUID id = UUID.randomUUID();
        given(staffIdentities.resolve("staff-1")).willReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity("ops@kredius.mx", "Ops Supervisora", null)));

        controller.decideIdentity(id, new ComplianceController.DecisionRequest("VERIFIED", "  INE y selfie coinciden "));

        verify(compliance).decideIdentity(id, "VERIFIED", "ops@kredius.mx", "INE y selfie coinciden");
    }

    @Test
    @DisplayName("si identity no resuelve el correo, firma con el id: un dictamen nunca queda sin autor")
    void withoutEmailItSignsWithTheId() {
        sesionDe("staff-1");
        UUID id = UUID.randomUUID();
        given(staffIdentities.resolve("staff-1")).willReturn(Optional.empty());

        controller.adjudicate(id, new ComplianceController.DecisionRequest("CLEARED", "homónimo: otra fecha"));

        verify(compliance).adjudicate(id, "CLEARED", "staff-1", "homónimo: otra fecha");
    }

    @Test
    @DisplayName("sin argumento no se cruza la red: 400 aquí mismo")
    void withoutNotesItIsRejectedBeforeCallingCompliance() {
        sesionDe("staff-1");

        assertThatThrownBy(() -> controller.decideIdentity(UUID.randomUUID(),
                new ComplianceController.DecisionRequest("VERIFIED", "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("argumento");
        verifyNoInteractions(compliance);
    }

    @Test
    @DisplayName("sin sesión no hay dictamen")
    void withoutSessionThereIsNoVerdict() {
        assertThatThrownBy(() -> controller.decideIdentity(UUID.randomUUID(),
                new ComplianceController.DecisionRequest("VERIFIED", "ok")))
                .isInstanceOf(ResponseStatusException.class);
        verify(compliance, never()).decideIdentity(any(), any(), any(), any());
    }

    @Test
    @DisplayName("un documento que no existe es 404, no un archivo vacío")
    void aMissingDocumentIsNotFound() {
        UUID id = UUID.randomUUID(), prospectId = UUID.randomUUID();
        given(compliance.verification(id)).willReturn(verificacion(id, prospectId));
        given(origination.documentContent(prospectId, "SELFIE")).willReturn(null);

        assertThatThrownBy(() -> controller.documentFile(id, "SELFIE"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SELFIE");
    }

    @Test
    @DisplayName("varias sesiones dictaminando a la vez: cada dictamen sale con SU autor")
    void concurrentSessionsKeepTheirOwnAuthor() throws Exception {
        int analistas = 8;
        given(staffIdentities.resolve(any())).willAnswer(inv ->
                Optional.of(new StaffIdentityResolver.StaffIdentity(inv.getArgument(0) + "@kredius.mx", null, null)));

        ExecutorService pool = Executors.newFixedThreadPool(analistas);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        Map<UUID, String> esperado = new ConcurrentHashMap<>();
        for (int i = 0; i < analistas; i++) {
            UUID id = UUID.randomUUID();
            String analista = "analista" + i;
            esperado.put(id, analista + "@kredius.mx");
            tareas.add(pool.submit(() -> {
                // El SecurityContext es por hilo: es exactamente lo que se comprueba.
                sesionDe(analista);
                salida.await();
                controller.decideIdentity(id, new ComplianceController.DecisionRequest("VERIFIED", "ok"));
                SecurityContextHolder.clearContext();
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        esperado.forEach((id, autor) -> verify(compliance).decideIdentity(id, "VERIFIED", autor, "ok"));
    }
}
