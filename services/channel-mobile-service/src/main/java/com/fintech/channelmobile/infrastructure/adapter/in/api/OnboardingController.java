package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.application.KycSessionData;
import com.fintech.channelmobile.application.KycSessionService;
import com.fintech.channelmobile.application.OtpService;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.*;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient.RegisterProspectPayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.DocumentUpload;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PartyClient;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestController
@Tag(name = "Onboarding", description = "Registro de prospecto, OTP y KYC")
class OnboardingController {

    private static final Logger log = LoggerFactory.getLogger(OnboardingController.class);

    private final OtpService otpService;
    private final KycSessionService kycSessionService;
    private final OriginationClient originationClient;
    private final PartyClient partyClient;

    OnboardingController(OtpService otpService,
                          KycSessionService kycSessionService,
                          OriginationClient originationClient,
                          PartyClient partyClient) {
        this.otpService = otpService;
        this.kycSessionService = kycSessionService;
        this.originationClient = originationClient;
        this.partyClient = partyClient;
    }

    // ── OTP ───────────────────────────────────────────────────────────────

    @Operation(summary = "Enviar OTP al teléfono")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OTP enviado"),
        @ApiResponse(responseCode = "429", description = "OTP activo vigente o límite de envíos alcanzado")
    })
    @PostMapping("/otp/send")
    ResponseEntity<Map<String, Object>> sendOtp(@Valid @RequestBody OtpSendRequest request) {
        if (otpService.hasActive(request.phone())) {
            log.warn("OTP blocked — active code exists phone={}", request.phone());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("success", false,
                                 "message", "Ya hay un OTP activo. Espera a que expire antes de solicitar otro."));
        }
        log.info("OTP send phone={}", request.phone());
        otpService.generate(request.phone());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OTP enviado al número registrado"));
    }

    @Operation(summary = "Verificar código OTP",
               description = "Verifica el código OTP. Continúa con POST /ocr/extract para iniciar el KYC.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resultado de la verificación")
    })
    @PostMapping("/otp/verify")
    ResponseEntity<Map<String, Object>> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        boolean verified = otpService.verify(request.phone(), request.code());
        if (verified) {
            log.info("OTP verified phone={}", request.phone());
            // preAuthToken: opaco, de un solo tramo (verify→kyc/submit). El BFF no
            // valida Authorization en /kyc/submit hoy — el contrato ya existe del lado
            // de la app (fa_onboarding envía Bearer $preAuthToken), pero nunca se emitía.
            String preAuthToken = java.util.UUID.randomUUID().toString();
            return ResponseEntity.ok(Map.of("verified", true, "preAuthToken", preAuthToken));
        }
        log.warn("OTP invalid phone={}", request.phone());
        return ResponseEntity.ok(Map.of("verified", false));
    }

    @Operation(summary = "Reenviar OTP",
               description = "Genera y envía un nuevo OTP, sobreescribiendo el anterior. "
                           + "Aplica el mismo rate limit que /otp/send.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OTP reenviado"),
        @ApiResponse(responseCode = "429", description = "Límite de envíos alcanzado")
    })
    @PostMapping("/otp/resend")
    ResponseEntity<Map<String, Object>> resendOtp(@Valid @RequestBody OtpSendRequest request) {
        log.info("OTP resend phone={}", request.phone());
        otpService.generate(request.phone());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "OTP reenviado al número registrado"));
    }

    // ── OCR ───────────────────────────────────────────────────────────────

    @Operation(summary = "Extraer datos del INE mediante OCR",
               description = "Stub: en producción se integrará con el servicio de OCR real.")
    @ApiResponse(responseCode = "200", description = "Datos extraídos del INE")
    @PostMapping("/ocr/extract")
    ResponseEntity<OcrExtractResponse> ocrExtract(@Valid @RequestBody OcrExtractRequest request) {
        // Stub OCR — retorna datos de ejemplo.
        // TODO: integrar con proveedor OCR (Incode, Jumio, etc.)
        //
        // La CURP se deriva del teléfono de la sesión en vez de ser una constante. Un OCR real
        // devuelve la de cada persona; una fija hacía que la segunda alta contra este ambiente
        // chocara con `DUPLICATE_PROSPECT` —origination valida por CURP— y el fallo aparecía como
        // un error del alta y no como lo que era: dos personas distintas con la misma credencial.
        return ResponseEntity.ok(new OcrExtractResponse(
                true,
                "EMMANUEL FRANCISCO",
                "RAMÍREZ",
                "HERNÁNDEZ",
                syntheticCurp(request.frente()),
                "26/03/1993",
                "COL. VILLA UNIVERSIDAD, CULIACÁN, SINALOA 80060",
                "9300326SL0001234",
                "2031",
                "H",
                "Sinaloa",
                "INE" + Instant.now().toEpochMilli()
        ));
    }

    /**
     * CURP con la forma correcta, derivada de la imagen de la credencial.
     *
     * <p>Es lo que hace un OCR real: dos INE distintas dan dos personas distintas, y la misma foto
     * siempre da la misma lectura —lo que mantiene idempotente el reintento del alta—. La constante
     * anterior hacía que la segunda persona que se diera de alta en este ambiente chocara con
     * `DUPLICATE_PROSPECT` en origination, y el fallo se leía como un error del alta en vez de como
     * lo que era: dos expedientes con la misma credencial.
     *
     * <p>Sólo varían las <b>dos últimas</b> posiciones, que es donde el registro civil pone el
     * desempate entre homónimos. No puede variar más: el patrón que valida {@code /kyc/submit} es
     * {@code [A-Z]{4}\d{6}[HM][A-Z]{5}[A-Z0-9]\d}, así que meter un dígito entre las cinco letras
     * produce una CURP que el propio BFF rechaza con 400 — y el alta muere sin decir por qué.
     */
    private static String syntheticCurp(String ineFrontImage) {
        int hash = Math.abs(java.util.Objects.hashCode(ineFrontImage));
        char[] alphabet = "ABCDEFGHIJKLMNPQRSTUVWXYZ0123456789".toCharArray();
        return "RAHE930326HSMLRM" + alphabet[hash % alphabet.length] + (hash / 7 % 10);
    }

    // ── KYC ───────────────────────────────────────────────────────────────

    @Operation(summary = "Enviar datos KYC completos del prospecto",
               description = "Almacena la sesión KYC y emite un folioKyc. Vigencia: 30 minutos.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "KYC almacenado — continúa con POST /auth/register"),
        @ApiResponse(responseCode = "400", description = "Error de validación")
    })
    @PostMapping("/kyc/submit")
    ResponseEntity<KycSubmitResponse> submitKyc(@Valid @RequestBody KycSubmitRequest request) {
        log.info("KYC submit phone={} curp={}", request.phone(), request.curp());
        AddressParts address = resolveAddress(request);

        KycSessionData session = new KycSessionData(
                request.phone(),
                request.nombres(),
                request.apellidoPaterno(),
                request.apellidoMaterno(),
                request.curp(),
                request.rfc(),
                normalizeDate(request.fechaNacimiento()),
                request.genero(),
                request.estadoNacimiento(),
                request.email(),
                address.calle(),
                address.numeroExterior(),
                request.numeroInterior(),
                address.colonia(),
                address.municipio(),
                address.ciudad(),
                address.estado(),
                address.codigoPostal(),
                request.aceptaAvisoPrivacidad(),
                request.aceptaCirculo(),
                Instant.now() // placeholder — KycSessionService asigna la expiración real
        );

        String folioKyc = kycSessionService.store(session);
        log.info("KYC stored folio={} phone={}", folioKyc, request.phone());
        return ResponseEntity.ok(new KycSubmitResponse(true, folioKyc));
    }

    @Operation(summary = "Crear credenciales de acceso para el prospecto",
               description = "Requiere el folioKyc de POST /kyc/submit. "
                           + "identity-service emite el JWT de acceso tras el registro exitoso.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Prospecto creado"),
        @ApiResponse(responseCode = "404", description = "folioKyc no encontrado o expirado"),
        @ApiResponse(responseCode = "409", description = "Prospecto ya existe (CURP o teléfono duplicado)")
    })
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Register request folioKyc={}", request.folioKyc());

        KycSessionData session = kycSessionService.retrieve(request.folioKyc())
                .orElseThrow(() -> {
                    log.warn("Register failed — folio not found or expired folioKyc={}", request.folioKyc());
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "folioKyc no encontrado o expirado. Reinicia el proceso de registro.");
                });

        RegisterProspectPayload payload = new RegisterProspectPayload(
                session.phone(),
                session.nombres(),
                session.apellidoPaterno(),
                session.apellidoMaterno(),
                session.curp(),
                session.rfc(),
                session.fechaNacimiento(),
                session.genero(),
                session.estadoNacimiento(),
                session.email(),
                session.calle(),
                session.numeroExterior(),
                session.numeroInterior(),
                session.colonia(),
                session.municipio(),
                session.ciudad(),
                session.estado(),
                session.codigoPostal(),
                session.aceptaAvisoPrivacidad(),
                session.aceptaCirculo(),
                request.password(),
                toDocumentPayloads(request.documents())
        );

        OriginationClient.ProspectResponse response = originationClient.registerProspect(payload);
        kycSessionService.invalidate(request.folioKyc());

        log.info("Register success folioKyc={} prospectId={} phone={} documentos={}",
                request.folioKyc(), response.prospectId(), session.phone(),
                request.documents() == null ? 0 : request.documents().size());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(true, response.prospectId(), response.message()));
    }

    @Operation(summary = "Subir o reemplazar un documento del expediente",
               description = "Para completar lo que faltó en el alta, o para reemplazar un "
                           + "documento que el analista rechazó. Sube al expediente del usuario "
                           + "autenticado: el prospecto se resuelve desde su sesión, no se recibe "
                           + "por parámetro.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Documento guardado"),
        @ApiResponse(responseCode = "404", description = "La sesión no corresponde a ningún prospecto")
    })
    @PutMapping("/kyc/documents/{documentType}")
    ResponseEntity<Map<String, Object>> uploadDocument(@PathVariable String documentType,
                                                       @Valid @RequestBody DocumentUpload body) {
        UUID prospectId = currentProspectId();
        boolean ok = originationClient.uploadDocument(
                prospectId, documentType, body.fileName(), body.contentType(), body.contentBase64());
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo guardar el documento. Vuelve a intentarlo.");
        }
        log.info("Documento {} actualizado para prospecto {}", documentType, prospectId);
        return ResponseEntity.ok(Map.of("success", true, "documentType", documentType));
    }

    /**
     * El prospecto de quien está usando la app.
     *
     * <p>El token identifica a un party; el expediente cuelga del prospecto, que es el mismo
     * sujeto antes de que se le abriera el crédito. Se resuelve aquí en vez de recibirlo por
     * parámetro: pedirlo en la ruta dejaría que cualquiera con una sesión válida escribiera en el
     * expediente ajeno con sólo conocer un identificador.
     */
    private UUID currentProspectId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida");
        }
        UUID partyId = UUID.fromString(String.valueOf(auth.getPrincipal()));
        var party = partyClient.getByPartyId(partyId);
        if (party == null || party.prospectId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Tu sesión no tiene un expediente asociado");
        }
        return party.prospectId();
    }

    /**
     * Traduce el expediente al contrato de origination.
     *
     * <p>Va dentro del alta y no en llamadas sueltas después: el alta de prospecto es pública
     * —cualquiera puede darse de alta— pero escribir en el expediente de un prospecto ya creado
     * no puede serlo, y subirlo aparte obligaría a abrir un endpoint público que acepta el UUID
     * de otro. Aquí la autorización es la misma del registro.
     */
    private static List<OriginationClient.DocumentPayload> toDocumentPayloads(List<DocumentUpload> documents) {
        if (documents == null) return List.of();
        return documents.stream()
                .map(d -> new OriginationClient.DocumentPayload(
                        d.documentType(), d.fileName(), d.contentType(), d.contentBase64()))
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * origination espera la fecha en ISO (yyyy-MM-dd). El OCR del INE / capturas viejas pueden
     * mandar dd/MM/yyyy — normalizamos aquí para no romper el registro del prospecto.
     */
    private String normalizeDate(String date) {
        if (date == null || date.isBlank()) return date;
        String d = date.trim();
        if (d.matches("\\d{2}/\\d{2}/\\d{4}")) {
            String[] p = d.split("/");
            return "%s-%s-%s".formatted(p[2], p[1], p[0]);
        }
        return d; // ya viene ISO (o formato que origination validará)
    }

    private AddressParts resolveAddress(KycSubmitRequest req) {
        if (hasStructuredAddress(req)) {
            return new AddressParts(req.calle(), req.numeroExterior(),
                    req.colonia(), req.municipio(), req.ciudad(),
                    req.estado(), req.codigoPostal());
        }
        return parseOcrDomicilio(req.domicilio());
    }

    private boolean hasStructuredAddress(KycSubmitRequest req) {
        return req.calle() != null && !req.calle().isBlank()
                && req.estado() != null && !req.estado().isBlank();
    }

    /**
     * Parsea domicilio en formato OCR: "COL. NOMBRE, CIUDAD, ESTADO CP"
     * Best-effort — no garantiza precisión para todos los formatos del INE.
     */
    private AddressParts parseOcrDomicilio(String domicilio) {
        if (domicilio == null || domicilio.isBlank()) {
            return new AddressParts("SIN CALLE", "S/N",
                    "SIN COLONIA", "SIN MUNICIPIO", "SIN CIUDAD", "SIN ESTADO", "00000");
        }
        String[] parts = domicilio.split(",");
        String colonia = parts.length > 0 ? parts[0].replaceAll("(?i)^COL\\.\\s*", "").trim() : "SIN COLONIA";
        String ciudad = parts.length > 1 ? parts[1].trim() : "SIN CIUDAD";
        String estadoCp = parts.length > 2 ? parts[2].trim() : "SIN ESTADO";

        String cp = "00000";
        String estado = estadoCp;
        String[] estadoParts = estadoCp.split("\\s+");
        if (estadoParts.length > 1) {
            String last = estadoParts[estadoParts.length - 1];
            if (last.matches("\\d{5}")) {
                cp = last;
                estado = estadoCp.substring(0, estadoCp.lastIndexOf(last)).trim();
            }
        }

        return new AddressParts("COL. " + colonia, "S/N", colonia, ciudad, ciudad, estado, cp);
    }

    private record AddressParts(
            String calle, String numeroExterior, String colonia,
            String municipio, String ciudad, String estado, String codigoPostal) {}
}
