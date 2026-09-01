plugins {
    `java-library`
    // Expone `src/testFixtures` para que los servicios reusen el arnés de integración
    // (`AbstractIntegrationTest`) en vez de repetir el bloque de Testcontainers en ~36 archivos.
    `java-test-fixtures`
    id("io.spring.dependency-management")
}

extra["springModulithVersion"] = "1.3.5"

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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    // Candado distribuido (com.fintech.shared.lock). `implementation` y no `api`: los servicios ya
    // traen el starter por su cuenta, así que no hace falta exponerlo en su classpath de compilación.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Redisson es el proveedor predeterminado del candado, pero entra como `compileOnly`: sólo los
    // servicios que declaren `redisson-spring-boot-starter` lo tendrán en runtime. Así `shared` no
    // se lo impone a los 21 servicios, y la autoconfiguración se activa por @ConditionalOnClass.
    compileOnly("org.redisson:redisson-spring-boot-starter:3.40.2")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.redisson:redisson-spring-boot-starter:3.40.2")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")

    // ── Arnés de integración compartido (testFixtures) ─────────────────────
    // `api` y no `implementation`: quien consuma los fixtures necesita estas clases
    // en su propio classpath de compilación para extender la clase base.
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.testcontainers:testcontainers:1.20.4")
    testFixturesApi("org.testcontainers:junit-jupiter:1.20.4")
    testFixturesApi("org.testcontainers:postgresql:1.20.4")
    // JdbcTemplate: lo usa el truncado de esquemas de AbstractIntegrationTest.
    testFixturesApi("org.springframework:spring-jdbc")
    // ContratoDeEvento: usa el mismo JsonDeserializer que construye el contenedor de Kafka.
    testFixturesApi("org.springframework.kafka:spring-kafka")
    testFixturesApi("com.fasterxml.jackson.core:jackson-databind")
    testFixturesApi("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // `ConfiguracionQueElEntornoNoEntregaTest` compara lo que cada servicio pide contra lo que
    // Compose entrega, leyendo ficheros de fuera de este módulo. Sin declararlos como entradas,
    // Gradle da la tarea por actualizada cuando sólo cambia un yml o el compose — y el guardián
    // deja de mirar justo cuando alguien introduce el defecto que vigila.
    inputs.file(rootProject.file("docker-compose.yml"))
        .withPropertyName("docker-compose")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree(rootProject.file("services")) { include("*/src/main/resources/application*.yml") })
        .withPropertyName("application-yml-de-los-servicios")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `ControlesQueNadiePuedeSatisfacerTest` compara los roles y capacidades que exige el código
    // contra los que identity siembra. Las dos mitades son entradas: si sólo se declara una, el
    // guardián se queda mirando una foto vieja de la otra.
    inputs.files(
        fileTree(rootProject.file("services")) { include("*/src/main/java/**/*.java") })
        .withPropertyName("fuentes-java-de-los-servicios")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree(rootProject.file("services/identity-service/src/main/resources/db/changelog/identity")) {
            include("*.sql")
        })
        .withPropertyName("catalogo-de-roles-y-capacidades")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Compatibilidad con el socket de Docker Desktop; mismo ajuste que el resto de los servicios.
    systemProperty("api.version", "1.44")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
