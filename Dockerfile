# ── Args ─────────────────────────────────────────────────────────────────────
# SERVICE: Gradle subproject name (e.g. identity-service, payments-service)
# Usage: docker build --build-arg SERVICE=identity-service -t fintech/identity-service .
# El contexto sigue siendo la raíz del repo, no services/.
ARG SERVICE=identity-service

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build
ARG SERVICE
WORKDIR /app

# Cache Gradle wrapper
COPY gradlew gradle/ ./gradle/
COPY gradlew ./
RUN chmod +x gradlew

# Cache root build files and shared module (common dependency for all services)
COPY build.gradle.kts settings.gradle.kts ./
COPY shared/ shared/

# Copy only the target service source
# Los servicios viven bajo `services/`; la ruta del proyecto Gradle sigue
# siendo `:${SERVICE}` (ver settings.gradle.kts).
COPY services/${SERVICE}/ services/${SERVICE}/

RUN ./gradlew :${SERVICE}:bootJar --no-daemon -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
ARG SERVICE
# Agente OTel horneado en la imagen (versión pineada, reproducible) — reemplaza el patrón
# runtime otel-agent-init + volumen. Ver observability-platform/docs/ARCHITECTURE_AND_PLAN.md §5.
ARG OTEL_AGENT_VERSION=2.30.0
WORKDIR /app

RUN addgroup -S fintech && adduser -S fintech -G fintech

ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN chown fintech:fintech /otel/opentelemetry-javaagent.jar

USER fintech

COPY --from=build /app/services/${SERVICE}/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
