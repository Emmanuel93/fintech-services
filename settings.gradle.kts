rootProject.name = "fintech-services"

// ── Core ──────────────────────────────────────────────────────────────────────
// El kernel compartido no es un servicio desplegable: se queda en la raíz.
include("shared")

// ── Services ──────────────────────────────────────────────────────────────────
// Los servicios viven bajo `services/`, pero conservan su ruta de proyecto Gradle
// de primer nivel (`:identity-service`, no `:services:identity-service`). Eso es
// deliberado: el Dockerfile invoca `./gradlew :${SERVICE}:bootJar` y los scripts y
// la documentación usan esa misma forma. Reubicar con `projectDir` mueve los
// archivos sin mover la coordenada con la que se les llama.
val services = listOf(
    "channel-mobile-service",
    "channel-backoffice-service",
    "identity-service",
    "configuration-service",
    "audit-service",
    "party-service",
    "channels-service",
    "scoring-service",
    "origination-service",
    "credit-product-service",
    "credit-portfolio-service",
    "charges-service",
    "payments-service",
    "wallet-service",
    "collections-service",
    "risk-service",
    "notifications-service",
    "accounting-service",
    "invoicing-service",
    "commission-service",
    "sales-org-service",
    "disbursement-service",
    "stp-service",
    "beneficiary-service"
)

services.forEach { name ->
    include(name)
    project(":$name").projectDir = file("services/$name")
}
