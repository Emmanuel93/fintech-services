plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

extra["springModulithVersion"] = "1.3.5"
extra["jjwtVersion"] = "0.12.6"
extra["springdocVersion"] = "2.8.8"
extra["totpVersion"] = "1.7.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

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
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // ── Spring Modulith ────────────────────────────────────────────────────
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")

    // ── JWT ────────────────────────────────────────────────────────────────
    implementation("io.jsonwebtoken:jjwt-api:${property("jjwtVersion")}")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:${property("jjwtVersion")}")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:${property("jjwtVersion")}")

    // ── TOTP (2FA) ─────────────────────────────────────────────────────────
    implementation("dev.samstevens.totp:totp:${property("totpVersion")}")

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
    // Testcontainers' shaded docker-java reads API version from the "api.version"
    // system property. docker.raw.sock (Docker Desktop's VM socket) requires ≥ 1.44;
    // the shaded default is 1.32, so we override it here.
    systemProperty("api.version", "1.44")
    // Ryuk (the cleanup container) can't mount docker.raw.sock; disable it so
    // Testcontainers uses JVM shutdown hooks for container cleanup instead.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
