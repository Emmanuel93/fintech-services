package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.out.IneReaderPort;
import com.fintech.beneficiary.application.port.out.LivenessPort;
import com.fintech.beneficiary.application.service.KycSubmissionService;
import com.fintech.beneficiary.application.service.PublicKycService;
import com.fintech.beneficiary.domain.PublicKycException;
import com.fintech.beneficiary.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * El contrato HTTP de la web pública: códigos, formato de error y dónde viaja cada credencial.
 *
 * <p>La página de React se está construyendo contra este contrato en paralelo; si una forma cambia
 * aquí, se rompe allá. Por eso se fija campo por campo.
 */
@WebMvcTest(controllers = {PublicKycController.class, PublicKycExceptionHandler.class,
        UploadLimitExceptionHandler.class, BeneficiaryExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PublicKycControllerTest {

    private static final String BASE = "/api/v1/beneficiary/public";

    @Autowired MockMvc mockMvc;

    @MockitoBean PublicKycService publicKyc;
    @MockitoBean KycSubmissionService submission;

    @Test
    @DisplayName("un token inválido y uno vencido responden exactamente lo mismo: 404 LIGA_NO_VALIDA")
    void invalidAndExpiredLookTheSame() throws Exception {
        given(publicKyc.viewInvite("inventado")).willThrow(new PublicKycException.InviteNotValid());
        given(publicKyc.viewInvite("vencido")).willThrow(new PublicKycException.InviteNotValid());

        String unknown = mockMvc.perform(get(BASE + "/invites/inventado"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LIGA_NO_VALIDA"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String expired = mockMvc.perform(get(BASE + "/invites/vencido"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(expired).isEqualTo(unknown);
    }

    @Test
    @DisplayName("la portada no necesita JWT y trae sólo los campos del contrato — sin el celular crudo")
    void invitePortadaShape() throws Exception {
        given(publicKyc.viewInvite("ok")).willReturn(new PublicKycService.InviteView(
                "Rosa Martínez", "María", new BigDecimal("18000"), new BigDecimal("868.06"), 12,
                new BigDecimal("10416.72"), "55 •• •• 90 37", "invited"));

        String body = mockMvc.perform(get(BASE + "/invites/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lenderName").value("Rosa Martínez"))
                .andExpect(jsonPath("$.beneficiaryFirstName").value("María"))
                .andExpect(jsonPath("$.amount").value(18000))
                .andExpect(jsonPath("$.fortnightlyPayment").value(868.06))
                .andExpect(jsonPath("$.termFortnights").value(12))
                .andExpect(jsonPath("$.totalToPay").value(10416.72))
                .andExpect(jsonPath("$.phoneMask").value("55 •• •• 90 37"))
                .andExpect(jsonPath("$.status").value("invited"))
                .andReturn().getResponse().getContentAsString();

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).size())
                .as("ni un campo más que los ocho del contrato").isEqualTo(8);
    }

    @Test
    @DisplayName("enviar OTP: {sent, phoneMask, resendInSeconds}; el cuarto es 429 con Retry-After")
    void sendOtp() throws Exception {
        given(publicKyc.sendOtp("ok")).willReturn(new PublicKycService.OtpSent(true, "55 •• •• 90 37", 45));
        mockMvc.perform(post(BASE + "/invites/ok/otp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.resendInSeconds").value(45));

        given(publicKyc.sendOtp("limite")).willThrow(new PublicKycException.TooManyAttempts("espera", 600));
        mockMvc.perform(post(BASE + "/invites/limite/otp"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.error").value("DEMASIADOS_INTENTOS"));
    }

    @Test
    @DisplayName("verificar: sessionToken y expiresAt; código equivocado es 422 con attemptsLeft")
    void verify() throws Exception {
        Instant exp = Instant.parse("2026-10-29T15:15:00Z");
        given(publicKyc.verifyOtp(eq("ok"), eq("123456"), eq("201.141.1.1"), any()))
                .willReturn(new PublicKycService.GuestSession("tok", exp));
        mockMvc.perform(post(BASE + "/invites/ok/otp/verify")
                        .header("X-Forwarded-For", "201.141.1.1, 10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").value("tok"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-29T15:15:00Z"));

        given(publicKyc.verifyOtp(eq("ok"), eq("000000"), any(), any()))
                .willThrow(new PublicKycException.WrongCode(3));
        mockMvc.perform(post(BASE + "/invites/ok/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CODIGO_INCORRECTO"))
                .andExpect(jsonPath("$.attemptsLeft").value(3));
    }

    @Test
    @DisplayName("sin X-Kyc-Session: 401 SESION_EXPIRADA — y la sesión nunca se lee de la ruta")
    void missingSessionHeader() throws Exception {
        given(publicKyc.state(isNull())).willThrow(new PublicKycException.SessionExpired());
        mockMvc.perform(get(BASE + "/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SESION_EXPIRADA"));
    }

    @Test
    @DisplayName("estado de la sesión: step, documents, data y consentsAccepted")
    void sessionState() throws Exception {
        given(publicKyc.state("s")).willReturn(new PublicKycService.SessionState(
                "LIVENESS", List.of("INE_BACK", "INE_FRONT"), null, false));
        mockMvc.perform(get(BASE + "/session").header("X-Kyc-Session", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("LIVENESS"))
                .andExpect(jsonPath("$.documents[0]").value("INE_BACK"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.consentsAccepted").value(false));
    }

    @Test
    @DisplayName("INE por multipart: devuelve {extracted:{…}}")
    void ineUpload() throws Exception {
        given(publicKyc.uploadIne(eq("s"), any(), any())).willReturn(new IneReaderPort.Extraction(
                "María Luisa", "Cortés", "Hernández", "COHM900612MDFRRR04", "COHM900612AB1", "1990-06-12",
                "Cerrada de Xochicalco", "44", "Narvarte Poniente", "Benito Juárez",
                "Ciudad de México", "Ciudad de México", "03020"));

        mockMvc.perform(multipart(BASE + "/session/ine")
                        .file(new MockMultipartFile("front", "f.jpg", "image/jpeg", new byte[]{1, 2}))
                        .file(new MockMultipartFile("back", "b.jpg", "image/jpeg", new byte[]{3, 4}))
                        .header("X-Kyc-Session", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extracted.curp").value("COHM900612MDFRRR04"))
                .andExpect(jsonPath("$.extracted.dateOfBirth").value("1990-06-12"))
                .andExpect(jsonPath("$.extracted.postalCode").value("03020"));

        then(publicKyc).should().uploadIne(eq("s"),
                argThat(u -> u != null && u.contentType().equals("image/jpeg") && u.content().length == 2),
                argThat(u -> u != null && u.content()[0] == 3));
    }

    @Test
    @DisplayName("selfie: {captured:true, review:'MANUAL'}")
    void liveness() throws Exception {
        given(publicKyc.uploadSelfie(eq("s"), any())).willReturn(new LivenessPort.Result(true, "MANUAL"));
        mockMvc.perform(multipart(BASE + "/session/liveness")
                        .file(new MockMultipartFile("selfie", "s.jpg", "image/jpeg", new byte[]{1}))
                        .header("X-Kyc-Session", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captured").value(true))
                .andExpect(jsonPath("$.review").value("MANUAL"));
    }

    @Test
    @DisplayName("datos inválidos: 422 DATOS_INVALIDOS con fields; válidos: {ok:true}")
    void data() throws Exception {
        org.mockito.BDDMockito.willThrow(new PublicKycException.InvalidData(Map.of("clabe", "no es válida")))
                .given(publicKyc).saveData(eq("malo"), any());
        mockMvc.perform(put(BASE + "/session/data").header("X-Kyc-Session", "malo")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clabe\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.fields.clabe").value("no es válida"));

        mockMvc.perform(put(BASE + "/session/data").header("X-Kyc-Session", "s")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyIncome\":12000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(put(BASE + "/session/data").header("X-Kyc-Session", "s")
                        .contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DATOS_INVALIDOS"));
    }

    @Test
    @DisplayName("consentimientos: la IP es el primer salto de X-Forwarded-For")
    void consentsUseForwardedIp() throws Exception {
        mockMvc.perform(post(BASE + "/session/consents").header("X-Kyc-Session", "s")
                        .header("X-Forwarded-For", "189.203.4.5, 10.0.0.1")
                        .header("User-Agent", "Mozilla/5.0 (iPhone)")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privacyNotice\":true,\"bureauAuthorization\":true,"
                               + "\"creditOwnership\":true,\"textVersion\":\"2026-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        then(publicKyc).should().acceptConsents(eq("s"),
                argThat(c -> c.textVersion().equals("2026-10") && c.bureauAuthorization()),
                eq("189.203.4.5"), eq("Mozilla/5.0 (iPhone)"));
    }

    @Test
    @DisplayName("enviar: {status, firstName, phoneMask}; incompleto es 409 con missing[]")
    void submit() throws Exception {
        given(submission.submit("s", "123456"))
                .willReturn(new KycSubmissionService.SubmitResult("bureauReady", "María", "55 •• •• 90 37"));
        mockMvc.perform(post(BASE + "/session/submit").header("X-Kyc-Session", "s")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("bureauReady"))
                .andExpect(jsonPath("$.firstName").value("María"))
                .andExpect(jsonPath("$.phoneMask").value("55 •• •• 90 37"));

        given(submission.submit("incompleta", "123456"))
                .willThrow(new PublicKycException.IncompleteFile(List.of("SELFIE", "CONSENTS")));
        mockMvc.perform(post(BASE + "/session/submit").header("X-Kyc-Session", "incompleta")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EXPEDIENTE_INCOMPLETO"))
                .andExpect(jsonPath("$.missing[0]").value("SELFIE"))
                .andExpect(jsonPath("$.missing[1]").value("CONSENTS"));
    }

    @Test
    @DisplayName("un error inesperado no filtra detalles internos: 503 genérico")
    void unexpectedErrorIsGeneric() throws Exception {
        given(publicKyc.viewInvite("boom")).willThrow(new IllegalStateException("jdbc: tabla secreta"));
        String body = mockMvc.perform(get(BASE + "/invites/boom"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICIO_NO_DISPONIBLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("tabla secreta");
    }
}
