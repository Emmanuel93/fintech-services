# base-service — Guía de implementación de servicios

> Monorepo: `fintech-services` · Stack: Java 21 · Spring Boot 3.4.5 · Spring Modulith 1.3.5
> Usa este documento como contexto base al crear cualquier nuevo `modulo-service`.

---

## 1. Registro en el monorepo

### settings.gradle.kts (raíz)
```kotlin
include("modulo-service")   // agregar en orden alfabético
```

### docker-compose.yml (raíz) — añadir servicio
```yaml
modulo-service:
  build:
    context: .
    dockerfile: Dockerfile
    args:
      SERVICE: modulo-service
  image: fintech/modulo-service:latest
  ports:
    - "${MODULO_PORT:-808X}:8080"
  environment:
    SPRING_PROFILES_ACTIVE: docker
    JWT_SECRET: ${JWT_SECRET:-change-me-in-production-use-at-least-256-bit-key}
    POSTGRES_USER: ${POSTGRES_USER:-fintech}
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-fintech}
    KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_healthy
    kafka:
      condition: service_started
  profiles: [modulo-service]
  restart: on-failure
```
El `Dockerfile` raíz ya está parametrizado con `ARG SERVICE` — no crear Dockerfile por servicio.

**Levantar solo este servicio + infraestructura:**
```bash
docker compose --profile modulo-service up -d
```

---

## 2. Estructura de directorios

```
modulo-service/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/com/fintech/modulo/
    │   │   ├── package-info.java                    ← boundary Spring Modulith
    │   │   ├── ModuloServiceApplication.java
    │   │   ├── domain/                              ← entidades, enums, excepciones de dominio
    │   │   ├── application/
    │   │   │   ├── port/
    │   │   │   │   ├── in/   ← interfaces UseCase
    │   │   │   │   └── out/  ← interfaces Repository, Port, Publisher
    │   │   │   ├── service/  ← implementaciones de use cases
    │   │   │   └── *.java    ← Command, Result, Properties records/clases
    │   │   └── infrastructure/
    │   │       ├── adapter/
    │   │       │   ├── in/api/   ← controllers, filters, exception handler, DTOs
    │   │       │   └── out/      ← persistence (JPA), messaging (Kafka), cache (Redis)
    │   │       └── config/       ← SecurityConfig, OpenApiConfig, beans
    │   └── resources/
    │       ├── application.yml
    │       ├── application-docker.yml
    │       └── db/changelog/modulo/
    │           ├── db.changelog-modulo.xml
    │           ├── 001-create-schema.sql
    │           └── 002-create-*.sql
    └── test/
        └── java/com/fintech/modulo/
            ├── ModuloServiceTest.java           ← unit: MockitoExtension
            └── ModuloAcceptanceTest.java        ← integración: @SpringBootTest + Testcontainers
```

---

## 3. build.gradle.kts

```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

extra["springModulithVersion"] = "1.3.5"
extra["springdocVersion"]      = "2.8.8"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

repositories { mavenCentral() }

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.5")
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

dependencies {
    implementation(project(":shared"))

    // ── Spring Boot Starters ───────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ── Spring Modulith ────────────────────────────────────────────────────
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")

    // ── OpenAPI / Swagger ──────────────────────────────────────────────────
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")

    // ── Kafka ──────────────────────────────────────────────────────────────
    implementation("org.springframework.kafka:spring-kafka")

    // ── Database ───────────────────────────────────────────────────────────
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    // ── Lombok ─────────────────────────────────────────────────────────────
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ── Test ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("api.version", "1.44")           // Docker Desktop socket compatibilidad
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
```

> Agregar versiones extra (ej. `jjwtVersion`, `totpVersion`) solo si el servicio las necesita.

---

## 4. Module boundary

```java
// src/main/java/com/fintech/modulo/package-info.java
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.modulo;
```

Cada servicio solo puede depender de `shared`. Dependencias entre servicios van por eventos Kafka o llamadas HTTP internas (nunca imports directos).

---

## 5. application.yml

```yaml
spring:
  application:
    name: modulo-service

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/fintech}
    username: ${SPRING_DATASOURCE_USERNAME:fintech}
    password: ${SPRING_DATASOURCE_PASSWORD:fintech}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:5}
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

  jpa:
    hibernate:
      ddl-auto: validate           # Liquibase gestiona el schema — nunca create/update
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: false
        jdbc:
          time_zone: UTC

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool: { max-active: 8, max-idle: 4, min-idle: 1 }

  cache:
    type: redis
    redis:
      key-prefix: "modulo:"
      use-key-prefix: true
      cache-null-values: false

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
        delivery.timeout.ms: 30000

  liquibase:
    change-log: classpath:db/changelog/modulo/db.changelog-modulo.xml
    enabled: true

  security:
    filter:
      order: 10

server:
  port: ${SERVER_PORT:8080}
  error:
    include-message: always
    include-binding-errors: always
    include-stacktrace: never

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
  info:
    env:
      enabled: true

info:
  app:
    name: ${spring.application.name}
    version: '@project.version@'
    java: '@java.version@'

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    display-request-duration: true
    operations-sorter: alpha
    tags-sorter: alpha
    try-it-out-enabled: true
    persist-authorization: true
    filter: true
  show-actuator: false

fintech:
  modulo:
    # propiedades específicas del servicio — mapear con @ConfigurationProperties
    jwt-secret: ${JWT_SECRET:change-me-in-production-use-at-least-256-bit-key}
```

## 5b. application-docker.yml

```yaml
# Activar con: SPRING_PROFILES_ACTIVE=docker
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/fintech
    username: ${POSTGRES_USER:fintech}
    password: ${POSTGRES_PASSWORD:fintech}
  data:
    redis:
      host: redis
      port: 6379
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}

logging:
  level:
    com.fintech: INFO
```

---

## 6. Liquibase — convenciones

### db.changelog-modulo.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <include file="db/changelog/modulo/001-create-schema.sql" relativeToChangelogFile="false"/>
    <include file="db/changelog/modulo/002-create-*.sql"      relativeToChangelogFile="false"/>
    <!-- Spring Modulith requiere esta tabla para event sourcing JPA -->
    <include file="db/changelog/modulo/NNN-create-event-publication.sql" relativeToChangelogFile="false"/>
</databaseChangeLog>
```

### 001-create-schema.sql
```sql
--liquibase formatted sql
--changeset modulo:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS modulo;
```

### Plantilla tabla
```sql
--liquibase formatted sql
--changeset modulo:002-create-entities author:system

CREATE TABLE modulo.entities (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    -- campos de negocio
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_entities PRIMARY KEY (id),
    CONSTRAINT chk_entity_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_entities_status ON modulo.entities (status);
```

### event_publication (Spring Modulith — siempre incluir)
```sql
--liquibase formatted sql
--changeset modulo:NNN-create-event-publication author:system
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID        NOT NULL,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);
```

> **Crítico:** `ddl-auto: validate` falla si esta tabla no existe en el schema por defecto (public).

---

## 7. Dominio — patrones

### Entidad
```java
@Entity
@Table(schema = "modulo", name = "entities")
public class ModuloEntity {

    @Id UUID id;
    // campos con columna explícita si el nombre difiere del campo Java
    @Column(name = "status", nullable = false) String status;
    @Column(nullable = false, updatable = false) Instant createdAt;
    Instant updatedAt;

    protected ModuloEntity() {}  // requerido por JPA

    public static ModuloEntity create(/* parámetros */) {
        var e = new ModuloEntity();
        e.id = UUID.randomUUID();
        e.status = "ACTIVE";
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    // métodos de negocio — nunca setters públicos
    public void activate() { this.status = "ACTIVE"; this.updatedAt = Instant.now(); }
    public boolean isActive() { return "ACTIVE".equals(status); }

    // getters solo — sin setters
    public UUID getId() { return id; }
}
```

### Excepción de dominio
```java
// Extiende SIEMPRE shared.DomainException
public class EntityNotFoundException extends DomainException {
    public EntityNotFoundException(String id) {
        super("MODULO_ENTITY_NOT_FOUND", "Entity not found: " + id);
    }
}
```

Prefijo del código de error: `MODULO_` (nombre del módulo en mayúsculas).

---

## 8. Application layer — patrones

### Puerto de entrada (use case)
```java
// application/port/in/CreateEntityUseCase.java
public interface CreateEntityUseCase {
    EntityResult create(CreateEntityCommand command);
}
```

### Comando (inmutable)
```java
// application/CreateEntityCommand.java
public record CreateEntityCommand(String field1, String field2, UUID requesterId) {}
```

### Resultado (sealed para flujos alternativos)
```java
// application/EntityResult.java
public sealed interface EntityResult {
    record Created(UUID id, Instant createdAt) implements EntityResult {}
    record ValidationFailed(String reason) implements EntityResult {}
}
```

### Puerto de salida (repositorio)
```java
// application/port/out/EntityRepository.java
public interface EntityRepository {
    Optional<ModuloEntity> findById(UUID id);
    ModuloEntity save(ModuloEntity entity);
    void deleteById(UUID id);
}
```

### Servicio de aplicación
```java
@Service
@Transactional
public class ModuloService implements CreateEntityUseCase, /* otros use cases */ {

    private final EntityRepository entityRepository;
    private final EventPublisher eventPublisher;
    // ... otros puertos

    // Constructor injection — sin @Autowired
    public ModuloService(EntityRepository entityRepository, EventPublisher eventPublisher) {
        this.entityRepository = entityRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public EntityResult create(CreateEntityCommand command) {
        // lógica de negocio
        ModuloEntity entity = ModuloEntity.create(command.field1(), command.field2());
        entityRepository.save(entity);
        eventPublisher.publish(EntityCreatedEvent.of(entity));
        return new EntityResult.Created(entity.getId(), entity.getCreatedAt());
    }
}
```

### Properties tipadas
```java
@ConfigurationProperties(prefix = "fintech.modulo")
@Validated
public class ModuloProperties {
    @NotBlank private String jwtSecret;
    private int maxRetries = 3;
    // getters + setters (Lombok @Data o manual)
}
// Agregar @EnableConfigurationProperties(ModuloProperties.class) en @Configuration
```

---

## 9. Infrastructure — adaptadores de salida

### JPA Repository (adapter out → persistence)
```java
// infrastructure/adapter/out/persistence/JpaEntityRepository.java
public interface JpaEntityRepository
        extends JpaRepository<ModuloEntity, UUID>, EntityRepository {

    // Spring Data deriva las implementaciones de los nombres
    // Los @Override fuerzan el contrato del puerto
    @Override
    Optional<ModuloEntity> findById(UUID id);
}
```

### Kafka Publisher
```java
@Component
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(EntityCreatedEvent event) {
        kafkaTemplate.send("modulo.entity-created", event.entityId().toString(), event);
    }
}
```

---

## 10. Infrastructure — adaptadores de entrada

### Controller (package-private)
```java
@RestController
@RequestMapping("/api/v1/modulo")
@Tag(name = "TX — Modulo", description = "Descripción del módulo")
class ModuloController {

    private final CreateEntityUseCase createEntityUseCase;
    // ... otros use cases

    ModuloController(CreateEntityUseCase createEntityUseCase) {
        this.createEntityUseCase = createEntityUseCase;
    }

    @Operation(summary = "Crear entidad")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Entidad creada"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos"),
        @ApiResponse(responseCode = "409", description = "Ya existe")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    ResponseEntity<EntityResponse> create(@Valid @RequestBody CreateEntityRequest request,
                                           HttpServletRequest httpRequest) {
        CreateEntityCommand command = new CreateEntityCommand(
                request.field1(), request.field2(),
                UUID.fromString(/* subject from security context */));

        return switch (createEntityUseCase.create(command)) {
            case EntityResult.Created(var id, var at) ->
                    ResponseEntity.status(201).body(new EntityResponse(id, at));
            case EntityResult.ValidationFailed(var reason) ->
                    throw new EntityValidationException(reason);
        };
    }
}
```

### Exception Handler
```java
@RestControllerAdvice
@Order(1)
class ModuloExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleNotFound(EntityNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    // Un @ExceptionHandler por excepción de dominio
    // Mapeado de status HTTP: 401 credenciales, 403 permisos, 404 no encontrado, 409 conflicto, 422/400 validación, 423 bloqueado
}
```

---

## 11. SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // endpoints públicos del servicio
                        .requestMatchers("/api/v1/modulo/public-endpoint").permitAll()
                        // endpoints de infraestructura — siempre incluir estos 4 bloques
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                         "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

> El `JwtAuthenticationFilter` valida el token JWT de `Authorization: Bearer ...` y lo registra en el `SecurityContextHolder`. Se reutiliza el del identity-service o se comparte via `shared`. No bloquea requests sin token — delega a la cadena de `authorizeHttpRequests`.

---

## 12. OpenApiConfig

```java
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "modulo-service",
        version = "1.0",
        description = "Descripción del módulo y su rol en la plataforma.",
        contact = @Contact(name = "Fintech Platform Team")
    ),
    servers = {
        @Server(url = "http://localhost:808X", description = "Local"),
        @Server(url = "http://modulo-service:8080", description = "Docker Compose")
    }
)
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "JWT obtenido de POST /api/v1/auth/login"
)
public class OpenApiConfig {}
```

---

## 13. Tests — patrones

### Unit test (servicio de aplicación)
```java
@ExtendWith(MockitoExtension.class)
class ModuloServiceTest {

    @Mock EntityRepository entityRepository;
    @Mock EventPublisher eventPublisher;

    ModuloService service;
    ModuloProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ModuloProperties();
        // inicializar valores de properties
        service = new ModuloService(entityRepository, eventPublisher /*, properties */);
    }

    @Test
    void create_validCommand_returnsCreated() {
        // given
        CreateEntityCommand command = new CreateEntityCommand("val1", "val2", UUID.randomUUID());
        given(entityRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publish(any());

        // when
        EntityResult result = service.create(command);

        // then
        assertThat(result).isInstanceOf(EntityResult.Created.class);
        then(entityRepository).should().save(any(ModuloEntity.class));
        then(eventPublisher).should().publish(any());
    }

    // Regla: si un @Mock no se usa en un test, NO lo stubees (UnnecessaryStubbingException)
    // Regla: si el servicio retorna early (null-check, guard), el colaborador no se invoca
}
```

### Controller test (WebMvcTest)
```java
@WebMvcTest(controllers = {ModuloController.class, ModuloExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "fintech.modulo.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo",
        // otras properties necesarias
})
class ModuloControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean CreateEntityUseCase createEntityUseCase;
    @MockBean TokenPort tokenPort;    // requerido por JwtAuthenticationFilter

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void create_validRequest_returns201() throws Exception {
        given(createEntityUseCase.create(any())).willReturn(
                new EntityResult.Created(UUID.randomUUID(), Instant.now()));

        mockMvc.perform(post("/api/v1/modulo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateEntityRequest("val1", "val2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }
}
```

### Acceptance test (SpringBootTest + Testcontainers)
```java
/**
 * Pruebas de aceptación de TX — Modulo.
 *
 * AC-1  Crear entidad → respuesta 201 con ID
 * AC-2  Obtener entidad → 200 con datos completos
 * AC-3  Sin token → 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ModuloAcceptanceTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired TokenPort tokenPort;    // para generar JWT de prueba

    @Test
    void ac1_create_returns201WithId() {
        ResponseEntity<EntityResponse> resp = restTemplate.exchange(
                "/api/v1/modulo", HttpMethod.POST,
                authEntity(new CreateEntityRequest("val1", "val2"), List.of("CUSTOMER")),
                EntityResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().id()).isNotNull();
    }

    @Test
    void ac_noToken_returns401() {
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/v1/modulo",
                new HttpEntity<>(new CreateEntityRequest("v", "v"), new HttpHeaders()),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private <T> HttpEntity<T> authEntity(T body, List<String> roles) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " +
                tokenPort.generateAccessToken(UUID.randomUUID(), roles, null));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
```

### application-test.properties (para acceptance tests con Testcontainers JDBC)
```properties
# src/test/resources/application-test.properties
spring.datasource.url=jdbc:tc:postgresql:16-alpine:///fintech?TC_INITSCRIPT=docker/postgres/init.sql
spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver
spring.kafka.bootstrap-servers=localhost:9092
```

---

## 14. Checklist antes de declarar el servicio completo

### Compilación y tests
- [ ] `./gradlew :modulo-service:compileJava` sin errores ni warnings de deprecación
- [ ] `./gradlew :modulo-service:test` — 0 failures, 0 errors
- [ ] Tests unitarios cubren: camino feliz, not found, validaciones, excepciones de dominio
- [ ] Tests de aceptación cubren: CRUD básico, autenticación (sin token → 401, con rol incorrecto → 403)

### Docker
- [ ] `docker compose --profile modulo-service build` compila sin errores
- [ ] `docker compose --profile modulo-service up -d` levanta todos los contenedores healthy
- [ ] `curl http://localhost:808X/actuator/health` → `{"status":"UP"}`
- [ ] `curl http://localhost:808X/swagger-ui/index.html` → 200
- [ ] `curl http://localhost:808X/v3/api-docs` → JSON con spec
- [ ] `curl http://localhost:808X/v3/api-docs.yaml` → 200

### Liquibase
- [ ] `001-create-schema.sql` — crea el schema `modulo`
- [ ] Tablas incluyen `event_publication` (Spring Modulith la requiere)
- [ ] `db.changelog-modulo.xml` incluye todos los changesets en orden
- [ ] `ddl-auto: validate` no lanza errores al arrancar

### Seguridad
- [ ] `/actuator/health`, `/actuator/info`, `/actuator/metrics` → 200 sin token
- [ ] `/error` en permitAll (evita 401 en forwards de errores)
- [ ] `/v3/api-docs`, `/v3/api-docs.yaml`, `/v3/api-docs/**`, `/swagger-ui.html`, `/swagger-ui/**` → 200 sin token
- [ ] Endpoints de negocio → 401 sin token

### Código
- [ ] Todas las excepciones extienden `DomainException` del shared
- [ ] `@ExceptionHandler` por cada excepción de dominio en el `ExceptionHandler`
- [ ] Todos los `ProblemDetail` tienen `setType(URI.create("https://fintech.com/errors/" + code))`
- [ ] Inyección por constructor en todos los beans (sin `@Autowired` en campos)
- [ ] `DeviceTracker`-equivalentes marcados como `public` si se necesitan mockear en tests externos al paquete

---

## 15. Errores comunes y sus soluciones

| Error | Causa | Solución |
|-------|-------|----------|
| `Schema-validation: missing table [event_publication]` | Spring Modulith requiere la tabla | Incluir `NNN-create-event-publication.sql` en Liquibase |
| `GET /actuator/health → 401` | Spring reenvía a `/error` que no está en permitAll | Agregar `.requestMatchers("/error").permitAll()` |
| `GET /actuator/health → 404` | `spring-boot-starter-actuator` no está en dependencies | Agregar el starter en `build.gradle.kts` |
| `UnnecessaryStubbingException` | Mock stubbado pero el método retorna early (null-guard) | Eliminar el stub que no se usa |
| `Bind for 0.0.0.0:9092 failed: port already allocated` | Kafka con dos listeners en mismo puerto | Usar un solo `PLAINTEXT://0.0.0.0:9092` listener sin PLAINTEXT_HOST |
| `kafka-topics healthcheck failing` | Herramienta no disponible inmediatamente | Usar `nc -z localhost 9092` + `start_period: 20s` |
| `Cannot access DeviceTracker from outside package` | Clase package-private | Agregar `public` a la clase y constructor |
| `LoginUseCase.login(String,String)` no existe | API cambió a command object | Usar `any(CommandClass.class)` en los mocks |

---

## 16. Referencia rápida — shared module

```
shared/src/main/java/com/fintech/shared/
├── exception/DomainException.java    ← abstract class con errorCode + message
└── event/DomainEvent.java            ← abstract class con eventId, occurredOn, correlationId
```

```java
// DomainException — cómo extender
public class MiException extends DomainException {
    public MiException(String detail) {
        super("MODULO_ERROR_CODE", detail);
    }
}
```

```java
// DomainEvent — cómo extender (para eventos Kafka vía Spring Modulith)
@Externalized("topic-name::#{#this.entityId}")  // routing key opcional
public class EntityCreatedEvent extends DomainEvent {
    private final UUID entityId;
    public EntityCreatedEvent(UUID entityId) {
        super();
        this.entityId = entityId;
    }
    public UUID getEntityId() { return entityId; }
}
```
