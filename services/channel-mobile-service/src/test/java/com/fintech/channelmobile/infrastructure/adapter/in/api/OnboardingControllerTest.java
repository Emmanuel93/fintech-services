package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.channelmobile.application.KycSessionData;
import com.fintech.channelmobile.application.KycSessionService;
import com.fintech.channelmobile.application.OtpService;
import com.fintech.channelmobile.infrastructure.adapter.out.client.MobileAuditClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient.ProspectResponse;
import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import com.fintech.channelmobile.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OnboardingController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(ChannelMobileProperties.class)
@TestPropertySource(properties = {
        "fintech.channel-mobile.identity-service-url=http://localhost:9999",
        "fintech.channel-mobile.origination-service-url=http://localhost:9998",
        "fintech.channel-mobile.otp-code-validation=false",
        "fintech.channel-mobile.otp-dev-code=123456",
        "fintech.channel-mobile.otp-expiry-minutes=5",
        "fintech.channel-mobile.otp-max-attempts=3",
        "fintech.channel-mobile.otp-max-sends-per-window=5",
        "fintech.channel-mobile.otp-send-window-minutes=60"
})
class OnboardingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // El slice de @WebMvcTest levanta WebMvcConfig, y con él el interceptor de la bitácora: sus
    // colaboradores tienen que existir aunque esta prueba no los ejercite. Sin ellos el contexto no
    // arranca y las catorce pruebas fallan por una razón que no tiene que ver con el onboarding.
    @MockitoBean MobileAuditClient mobileAuditClient;
    @MockitoBean CustomerIdentityResolver customerIdentityResolver;

    @MockitoBean OtpService otpService;
    @MockitoBean KycSessionService kycSessionService;
    @MockitoBean OriginationClient originationClient;
    @MockitoBean PartyClient partyClient;

    static final String PHONE = "5512345678";

    private String json(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    // ── POST /otp/send ────────────────────────────────────────────────────

    @Test
    void sendOtp_validPhone_noActiveOtp_returns200() throws Exception {
        given(otpService.hasActive(PHONE)).willReturn(false);

        mockMvc.perform(post("/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void sendOtp_activeOtpExists_returns429() throws Exception {
        given(otpService.hasActive(PHONE)).willReturn(true);

        mockMvc.perform(post("/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void sendOtp_rateLimitExceeded_returns429() throws Exception {
        given(otpService.hasActive(PHONE)).willReturn(false);
        given(otpService.generate(PHONE))
                .willThrow(new ResponseStatusException(TOO_MANY_REQUESTS, "Límite alcanzado"));

        mockMvc.perform(post("/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void sendOtp_invalidPhone_returns400() throws Exception {
        mockMvc.perform(post("/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", "123"))))
                .andExpect(status().isBadRequest());
    }

    // ── POST /otp/verify ──────────────────────────────────────────────────

    @Test
    void verifyOtp_correctCode_returns200WithVerifiedTrue() throws Exception {
        given(otpService.verify(PHONE, "123456")).willReturn(true);

        mockMvc.perform(post("/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE, "code", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                // Verificar el OTP emite el pase de un solo tramo con el que se manda el KYC. La
                // app ya lo enviaba como Bearer y el canal nunca lo emitía, así que el alta se
                // hacía sin nada que probara que ese teléfono acababa de verificarse.
                .andExpect(jsonPath("$.preAuthToken").isNotEmpty());
    }

    @Test
    void verifyOtp_wrongCode_returns200WithVerifiedFalse() throws Exception {
        given(otpService.verify(PHONE, "000000")).willReturn(false);

        mockMvc.perform(post("/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE, "code", "000000"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.preAuthToken").doesNotExist());
    }

    @Test
    void verifyOtp_invalidCode_returns400() throws Exception {
        mockMvc.perform(post("/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE, "code", "12"))))
                .andExpect(status().isBadRequest());
    }

    // ── POST /otp/resend ──────────────────────────────────────────────────

    @Test
    void resendOtp_validRequest_returns200() throws Exception {
        mockMvc.perform(post("/otp/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        then(otpService).should().generate(PHONE);
    }

    @Test
    void resendOtp_rateLimitExceeded_returns429() throws Exception {
        given(otpService.generate(PHONE))
                .willThrow(new ResponseStatusException(TOO_MANY_REQUESTS, "Límite alcanzado"));

        mockMvc.perform(post("/otp/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("phone", PHONE))))
                .andExpect(status().isTooManyRequests());
    }

    // ── POST /ocr/extract — endpoint público, sin token ───────────────────

    @Test
    void ocrExtract_publicEndpoint_noTokenRequired_returns200() throws Exception {
        mockMvc.perform(post("/ocr/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("frente", "dGVzdA==", "reverso", "dGVzdA=="))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── POST /kyc/submit — endpoint público, sin token ────────────────────

    @Test
    void submitKyc_publicEndpoint_noTokenRequired_returns200WithFolioKyc() throws Exception {
        given(kycSessionService.store(any())).willReturn("KYC-202605-AB12CD34");

        mockMvc.perform(post("/kyc/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validKycPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folioKyc").value("KYC-202605-AB12CD34"));
    }

    @Test
    void submitKyc_invalidCurp_returns400() throws Exception {
        String badKyc = validKycPayload().replace("RAHE930326HSMLRM09", "INVALID");

        mockMvc.perform(post("/kyc/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badKyc))
                .andExpect(status().isBadRequest());
    }

    // ── POST /auth/register — endpoint público, sin token ─────────────────

    @Test
    void register_validFolioKyc_returns201() throws Exception {
        KycSessionData session = kycSession(PHONE);
        given(kycSessionService.retrieve("KYC-202605-FOLIOTEST")).willReturn(Optional.of(session));
        given(originationClient.registerProspect(any()))
                .willReturn(new ProspectResponse("PROSPECT-123", "RAHE930326HSMLRM09", "+52" + PHONE,
                        Instant.now().toString(), Instant.now().plusSeconds(86400).toString(), "Prospecto creado"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "password", "SecurePass1",
                                "folioKyc", "KYC-202605-FOLIOTEST"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.userId").value("PROSPECT-123"));
    }

    @Test
    void register_folioNotFound_returns404() throws Exception {
        given(kycSessionService.retrieve(any())).willReturn(Optional.empty());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "password", "SecurePass1",
                                "folioKyc", "KYC-202605-INEXISTENTE"))))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private KycSessionData kycSession(String phone) {
        return new KycSessionData(
                phone, "EMMANUEL", "RAMÍREZ", "HERNÁNDEZ",
                "RAHE930326HSMLRM09", "RAHE930326XXX",
                "1993-03-26", "H", "Sinaloa",
                "test@test.com", "Av. Principal", "100", null,
                "Centro", "Culiacán", "Culiacán", "Sinaloa", "80060",
                true, true,
                Instant.now().plusSeconds(1800));
    }

    private String validKycPayload() {
        return """
                {
                  "phone": "%s",
                  "nombres": "EMMANUEL",
                  "apellidoPaterno": "RAMÍREZ",
                  "apellidoMaterno": "HERNÁNDEZ",
                  "curp": "RAHE930326HSMLRM09",
                  "rfc": "RAHE930326XXX",
                  "fechaNacimiento": "1993-03-26",
                  "genero": "H",
                  "estadoNacimiento": "Sinaloa",
                  "email": "test@test.com",
                  "calle": "Av. Principal",
                  "numeroExterior": "100",
                  "colonia": "Centro",
                  "municipio": "Culiacán",
                  "ciudad": "Culiacán",
                  "estado": "Sinaloa",
                  "codigoPostal": "80060",
                  "aceptaAvisoPrivacidad": true,
                  "aceptaCirculo": true
                }
                """.formatted(PHONE);
    }
}
