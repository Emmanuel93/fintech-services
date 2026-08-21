package com.fintech.identity.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "identity-service",
                version = "1.0",
                description = """
                        Servicio de autenticación y gestión de tokens JWT.

                        **Prerrequisito síncrono** para todos los servicios de la plataforma: \
                        ningún request pasa a otro servicio sin pasar antes por `GET /api/v1/auth/validate`.

                        **Flujos soportados:**
                        - Party auth — `username` + `password`, con 2FA TOTP opcional
                        - Client auth (M2M) — `client_id` + `secret` con whitelist de IPs por CIDR

                        **Eventos Kafka:** cada intento de login emite a `identity.login-attempted` \
                        con metadata de red, geolocalización y dispositivo para cumplimiento regulatorio \
                        (CNBV, PCI-DSS, SOC2, GDPR).

                        **Seguridad:** usar el botón **Authorize** e ingresar el `accessToken` obtenido \
                        del endpoint `/login` o `/clients/token`.
                        """,
                contact = @Contact(name = "Fintech Platform Team")
        ),
        servers = {
                @Server(url = "http://localhost:8080", description = "Local"),
                @Server(url = "http://identity-service:8080", description = "Docker Compose")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT obtenido de POST /api/v1/auth/login o POST /api/v1/auth/clients/token"
)
public class OpenApiConfig {}
