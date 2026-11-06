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

# JasperReports mide el texto con AWT, y en Alpine AWT necesita freetype y fontconfig aunque la fuente
# venga empaquetada en el jar. Sólo el servicio que genera PDF las lleva; los demás no cambian.
RUN if [ "$SERVICE" = "statement-service" ]; then apk add --no-cache fontconfig freetype ttf-dejavu; fi

ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN chown fintech:fintech /otel/opentelemetry-javaagent.jar

USER fintech

COPY --from=build /app/services/${SERVICE}/build/libs/*.jar app.jar

EXPOSE 8080

# Every memory pool of the JVM is bounded, so the process fits in its container's limit and is never killed by the
# kernel; it may run slower, it does not fall over.
#   heap         50 % of the limit (measured in use: 80-95 MB of a 512 MiB service)
#   metaspace    192 MB at most (about 150 MB with the OpenTelemetry agent, about 100 without it)
#   code cache   64 MB, C1 only (TieredStopAtLevel=1): C2's compilations are what spike native memory
#   direct       64 MB (NIO and Netty buffers)
#   GC           Serial: a heap of a few hundred MB does not need G1, and G1's own structures cost memory
# A heap that runs out exits the process (ExitOnOutOfMemoryError) and the container restarts it, instead of a JVM
# limping on with half its threads dead. With the heap at 75 % and nothing else bounded every 512 MiB service sat at
# 90-99 % of its limit, and the kernel killed credit-portfolio and collections mid-run (3 Nov demo).
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=50.0", \
    "-XX:MaxMetaspaceSize=192m", \
    "-XX:ReservedCodeCacheSize=64m", \
    "-XX:TieredStopAtLevel=1", \
    "-XX:MaxDirectMemorySize=64m", \
    "-XX:+UseSerialGC", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
