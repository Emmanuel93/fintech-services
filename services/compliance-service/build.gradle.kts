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
    // Candado de crones: una sola réplica corre cada @Scheduled (ver SchedulingLockAutoConfiguration).
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.springframework.modulith:spring-modulith-starter-core")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")
    implementation("org.springframework.kafka:spring-kafka")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Arnés compartido: AbstractIntegrationTest + contenedores reutilizados (BK-43).
    testImplementation(testFixtures(project(":shared")))
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    // Las pruebas siembran sus propias listas: la sincronización de arranque las mezclaría con las
    // del publicador simulado y cambiaría lo que cada prueba espera encontrar.
    systemProperty("fintech.compliance.watchlist-sync.on-startup", "false")
    // Y ninguna prueba debe alcanzar un party de verdad: con el stack local levantado, el default
    // `localhost:8083` es el party-service real y las pruebas dependían de lo que hubiera en él.
    // El puerto 9 rechaza la conexión al instante: el directorio contesta «no sé», que es el caso
    // que las pruebas de integración cubren.
    systemProperty("fintech.compliance.party-service-url", "http://localhost:9")
    useJUnitPlatform()
    systemProperty("api.version", "1.44")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
