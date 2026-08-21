/**
 * Canal Mobile — BFF (Backend For Frontend) para la app móvil.
 *
 * Actúa como proxy inteligente entre la app Flutter y los servicios internos:
 * identity-service (auth/tokens) y origination-service (alta de prospectos).
 *
 * Flujo principal: OTP → OCR → KYC submit → Register → Login
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Channel Mobile — BFF"
)
package com.fintech.channelmobile;
