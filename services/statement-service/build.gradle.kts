plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

extra["springModulithVersion"] = "1.3.5"
extra["springdocVersion"]  = "2.8.8"
extra["jasperVersion"]     = "7.0.8"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

repositories { mavenCentral() }

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.5")
        // `shared` trae Modulith; sin su BOM la versión queda vacía.
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

configurations.all {
    // jasperreports-pdf 7 pide un fork de OpenPDF que sólo vive en el repositorio de Jaspersoft. El
    // OpenPDF de Maven Central de la misma línea (1.3.x, paquete com.lowagie) es el que exporta: así
    // la construcción no depende de un repositorio externo que puede no estar.
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.librepdf:openpdf"))
            .using(module("com.github.librepdf:openpdf:1.3.43"))
            .because("el fork 1.3.43.jaspersoft.2 no está en Maven Central")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")
    implementation("org.springframework.kafka:spring-kafka")

    // Los documentos: plantillas JRXML compiladas al arrancar y exportadas a PDF.
    implementation("net.sf.jasperreports:jasperreports:${property("jasperVersion")}")
    implementation("net.sf.jasperreports:jasperreports-pdf:${property("jasperVersion")}")
    // DejaVu empaquetada: el PDF sale igual en la laptop y en el pod, sin depender de las fuentes del SO.
    implementation("net.sf.jasperreports:jasperreports-fonts:${property("jasperVersion")}")
    // Jasper compila las expresiones de la plantilla a Java al arrancar. La imagen corre con un JRE,
    // que no trae javac: sin el compilador de Eclipse (JDT, va dentro del jar) el pod no arranca.
    // `jasperreports.properties` lo fija para que las pruebas usen el mismo que producción.
    implementation("net.sf.jasperreports:jasperreports-jdt:${property("jasperVersion")}")
    // jasperreports-jdt trae ECJ 3.21 (2020), que no conoce las clases de Java 21. 3.40 sí.
    implementation("org.eclipse.jdt:ecj:3.40.0")

    // Candado de crones: el reintento de fallidos corre en una sola réplica.
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.liquibase:liquibase-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":shared")))
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // Para leer el texto de los PDF generados en las pruebas.
    testImplementation("org.apache.pdfbox:pdfbox:3.0.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("api.version", "1.44")
    systemProperty("java.awt.headless", "true")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
}
