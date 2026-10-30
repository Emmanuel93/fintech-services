package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.application.port.out.IneReaderPort;
import com.fintech.beneficiary.application.port.out.LivenessPort;
import com.fintech.beneficiary.application.service.KycSubmissionService;
import com.fintech.beneficiary.application.service.PublicKycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * La web pública de KYC de la beneficiaria. Sin JWT y sin cuenta.
 *
 * <p>Dos credenciales, y por qué viajan donde viajan:
 *
 * <ul>
 *   <li>El <b>token de la liga</b> va en la ruta ({@code /invites/{token}}) porque llega en una URL
 *       de WhatsApp y no hay otro lugar donde ponerlo. Sólo abre la portada y el OTP.</li>
 *   <li>El <b>token de sesión</b> va en el header {@code X-Kyc-Session} y <b>nunca</b> en la ruta:
 *       las rutas terminan en los access logs de nginx, y esa es la credencial que abre su
 *       expediente.</li>
 * </ul>
 *
 * <p>Los errores salen como {@code {error, message, …}} y no como {@code ProblemDetail}: del otro
 * lado hay una página que decide qué pantalla mostrar por el código. Los mapea
 * {@link PublicKycExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/beneficiary/public")
@Tag(name = "KYC web · pública", description = "Los pasos de la beneficiaria, sin JWT")
class PublicKycController {

    static final String SESSION_HEADER = "X-Kyc-Session";

    private final PublicKycService publicKyc;
    private final KycSubmissionService submission;

    PublicKycController(PublicKycService publicKyc, KycSubmissionService submission) {
        this.publicKyc  = publicKyc;
        this.submission = submission;
    }

    // ── Con el token de la liga ──────────────────────────────────────────────────────────────

    @Operation(summary = "Portada de la liga",
               description = "Quién presta, cuánto y a qué plazo. Sin PII más allá de su primer nombre. "
                           + "Token inválido, vencido, revocado o ya usado: el mismo 404.")
    @GetMapping("/invites/{token}")
    PublicKycService.InviteView invite(@PathVariable String token) {
        return publicKyc.viewInvite(token);
    }

    @Operation(summary = "Mandar el código OTP", description = "Máximo 3 por cada 15 minutos.")
    @PostMapping("/invites/{token}/otp")
    PublicKycService.OtpSent sendOtp(@PathVariable String token) {
        return publicKyc.sendOtp(token);
    }

    @Operation(summary = "Verificar el código y abrir la sesión de invitado",
               description = "5 códigos equivocados pausan la liga una hora.")
    @PostMapping("/invites/{token}/otp/verify")
    PublicKycService.GuestSession verifyOtp(@PathVariable String token,
                                            @RequestBody(required = false) CodeRequest body,
                                            HttpServletRequest request) {
        return publicKyc.verifyOtp(token, body == null ? null : body.code(),
                clientIp(request), request.getHeader(HttpHeaders.USER_AGENT));
    }

    // ── Con la sesión de invitado ────────────────────────────────────────────────────────────

    @Operation(summary = "Dónde va", description = "Para retomar en el paso correcto al reabrir la liga.")
    @GetMapping("/session")
    PublicKycService.SessionState session(@RequestHeader(value = SESSION_HEADER, required = false) String session) {
        return publicKyc.state(session);
    }

    @Operation(summary = "Frente y reverso de la INE", description = "Fotos o PDF, 8 MB cada uno.")
    @PostMapping(value = "/session/ine", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, IneReaderPort.Extraction> ine(@RequestHeader(value = SESSION_HEADER, required = false) String session,
                                              @RequestPart(value = "front", required = false) MultipartFile front,
                                              @RequestPart(value = "back", required = false) MultipartFile back) {
        return Map.of("extracted", publicKyc.uploadIne(session, upload(front), upload(back)));
    }

    @Operation(summary = "Selfie de prueba de vida",
               description = "Se guarda y no se dictamina: la compara una persona contra la INE.")
    @PostMapping(value = "/session/liveness", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LivenessPort.Result liveness(@RequestHeader(value = SESSION_HEADER, required = false) String session,
                                 @RequestPart(value = "selfie", required = false) MultipartFile selfie) {
        return publicKyc.uploadSelfie(session, upload(selfie));
    }

    @Operation(summary = "Tus datos", description = "Incluye la CLABE, obligatoria y con dígito verificador.")
    @PutMapping("/session/data")
    Map<String, Object> data(@RequestHeader(value = SESSION_HEADER, required = false) String session,
                             @RequestBody(required = false) KycData body) {
        publicKyc.saveData(session, body);
        return Map.of("ok", true);
    }

    @Operation(summary = "Los tres consentimientos", description = "Se graba IP, navegador, hora y versión del texto.")
    @PostMapping("/session/consents")
    Map<String, Object> consents(@RequestHeader(value = SESSION_HEADER, required = false) String session,
                                 @RequestBody(required = false) PublicKycService.ConsentsCommand body,
                                 HttpServletRequest request) {
        publicKyc.acceptConsents(session, body, clientIp(request), request.getHeader(HttpHeaders.USER_AGENT));
        return Map.of("ok", true);
    }

    @Operation(summary = "Firmar con el OTP y enviar",
               description = "Todo o nada: prospecto, documentos, Party, relación y constancia de buró.")
    @PostMapping("/session/submit")
    KycSubmissionService.SubmitResult submit(@RequestHeader(value = SESSION_HEADER, required = false) String session,
                                             @RequestBody(required = false) CodeRequest body) {
        return submission.submit(session, body == null ? null : body.code());
    }

    record CodeRequest(String code) {}

    // ── Plomería ─────────────────────────────────────────────────────────────────────────────

    /**
     * La IP de ella, no la del gateway: el primer salto de {@code X-Forwarded-For}. Es evidencia
     * de la constancia de buró, y la IP del nginx sería la misma para todas las beneficiarias.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }

    private static PublicKycService.Upload upload(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            return new PublicKycService.Upload(file.getBytes(), file.getContentType(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
