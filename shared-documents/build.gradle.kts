plugins {
    `java-library`
    id("io.spring.dependency-management")
}

// The documents every service prints (statements, payment receipts): the model a template is filled with, the
// formats, the brand's palette, the Jasper engine, the Inter typeface and the logotype. A library, not a service:
// statement-service and receipt-service each deploy on their own, with one implementation of how a PDF is made.
extra["jasperVersion"] = "7.0.8"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.5")
    }
}

configurations.all {
    // jasperreports-pdf 7 asks for an OpenPDF fork that only lives in Jaspersoft's repository; Maven Central's OpenPDF
    // of the same line (1.3.x, package com.lowagie) is the one that exports. Each service that depends on this
    // module declares the same substitution: resolution happens in the consumer's configuration.
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.librepdf:openpdf"))
            .using(module("com.github.librepdf:openpdf:1.3.43"))
            .because("el fork 1.3.43.jaspersoft.2 no está en Maven Central")
    }
}

dependencies {
    // `api`: the services compile against Jasper's types only through this module, but they run its engine.
    api("net.sf.jasperreports:jasperreports:${property("jasperVersion")}")
    api("net.sf.jasperreports:jasperreports-pdf:${property("jasperVersion")}")
    // DejaVu packaged: the PDF comes out the same on the laptop and in the pod.
    api("net.sf.jasperreports:jasperreports-fonts:${property("jasperVersion")}")
    // Jasper compiles the templates' expressions at start-up. The image runs a JRE without javac: the Eclipse
    // compiler (JDT) travels in the jar. `jasperreports.properties` pins it so tests use the same as production.
    api("net.sf.jasperreports:jasperreports-jdt:${property("jasperVersion")}")
    // jasperreports-jdt brings ECJ 3.21 (2020), which does not know Java 21's classes. 3.40 does.
    api("org.eclipse.jdt:ecj:3.40.0")

    implementation("org.springframework:spring-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
