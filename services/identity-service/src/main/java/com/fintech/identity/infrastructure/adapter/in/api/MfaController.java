package com.fintech.identity.infrastructure.adapter.in.api;

import com.fintech.identity.application.MfaEnrollmentResult;
import com.fintech.identity.application.MfaVerifyCommand;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "T1 — MFA", description = "Two-Factor Authentication via TOTP (Google Authenticator / Authy)")
class MfaController {

    private final EnrollMfaUseCase enrollMfaUseCase;
    private final ConfirmMfaEnrollmentUseCase confirmMfaEnrollmentUseCase;
    private final VerifyMfaUseCase verifyMfaUseCase;
    private final DisableMfaUseCase disableMfaUseCase;

    MfaController(EnrollMfaUseCase enrollMfaUseCase,
                  ConfirmMfaEnrollmentUseCase confirmMfaEnrollmentUseCase,
                  VerifyMfaUseCase verifyMfaUseCase,
                  DisableMfaUseCase disableMfaUseCase) {
        this.enrollMfaUseCase = enrollMfaUseCase;
        this.confirmMfaEnrollmentUseCase = confirmMfaEnrollmentUseCase;
        this.verifyMfaUseCase = verifyMfaUseCase;
        this.disableMfaUseCase = disableMfaUseCase;
    }

    @Operation(summary = "Iniciar enrolamiento TOTP — devuelve secret y QR URI para escanear")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Secret y QR URI generados — escanear con Google Authenticator / Authy"),
        @ApiResponse(responseCode = "409", description = "MFA ya activo para este party")
    })
    @PostMapping("/enroll")
    ResponseEntity<MfaEnrollResponse> enroll(@AuthenticationPrincipal String partyIdStr) {
        UUID partyId = UUID.fromString(partyIdStr);
        MfaEnrollmentResult result = enrollMfaUseCase.enroll(partyId);
        return ResponseEntity.ok(new MfaEnrollResponse(result.totpSecret(), result.qrUri()));
    }

    @Operation(summary = "Confirmar enrolamiento — primer código TOTP válido activa el 2FA")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "2FA activado"),
        @ApiResponse(responseCode = "400", description = "Código inválido o expirado"),
        @ApiResponse(responseCode = "404", description = "MFA no iniciado para este party")
    })
    @PostMapping("/enroll/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmEnrollment(@AuthenticationPrincipal String partyIdStr,
                           @Valid @RequestBody MfaEnrollConfirmRequest request) {
        confirmMfaEnrollmentUseCase.confirmEnrollment(UUID.fromString(partyIdStr), request.code());
    }

    @Operation(summary = "Verificar TOTP tras login — completa la autenticación y devuelve tokens")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tokens emitidos"),
        @ApiResponse(responseCode = "400", description = "Código inválido o mfaToken expirado")
    })
    @PostMapping("/verify")
    ResponseEntity<TokenResponse> verify(@Valid @RequestBody MfaVerifyRequest request,
                                          HttpServletRequest httpRequest) {
        MfaVerifyCommand command = new MfaVerifyCommand(
                request.mfaToken(),
                request.code(),
                ClientIpResolver.resolve(httpRequest),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Request-ID"));

        TokenPair pair = verifyMfaUseCase.verify(command);
        return ResponseEntity.ok(TokenResponse.of(pair.accessToken(), pair.refreshToken(), pair.expiresIn()));
    }

    @Operation(summary = "Deshabilitar 2FA — requiere código TOTP vigente para confirmar")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "2FA deshabilitado"),
        @ApiResponse(responseCode = "400", description = "Código inválido"),
        @ApiResponse(responseCode = "404", description = "MFA no activo para este party")
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@AuthenticationPrincipal String partyIdStr,
                 @Valid @RequestBody MfaDisableRequest request) {
        disableMfaUseCase.disable(UUID.fromString(partyIdStr), request.code());
    }

}
