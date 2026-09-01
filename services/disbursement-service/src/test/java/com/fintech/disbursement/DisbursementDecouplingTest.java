package com.fintech.disbursement;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DC-1 — el desacople como prueba ejecutable, no como afirmación en un diagrama.
 *
 * <p>Si alguien mete un {@code creditAccountId} en el núcleo "sólo por esta vez", esto falla en CI.
 * Esa es la única forma de que la promesa de comercializar el servicio por separado sobreviva a
 * seis meses de prisas.
 */
@AnalyzeClasses(packages = "com.fintech.disbursement",
                importOptions = ImportOption.DoNotIncludeTests.class)
class DisbursementDecouplingTest {

    /** El núcleo no sabe qué es un crédito. */
    @ArchTest
    static final ArchRule nucleo_agnostico_de_credito =
            noClasses().that().resideInAnyPackage("..disbursement.domain..", "..disbursement.application..")
                    .should().haveSimpleNameContaining("Credit")
                    .orShould().haveSimpleNameContaining("Disposition")
                    .orShould().haveSimpleNameContaining("Wallet")
                    .orShould().haveSimpleNameContaining("Obligor")
                    .because("el núcleo de payouts debe poder venderse sin el core de crédito (§7.2)");

    /** Y tampoco importa nada de otro servicio. */
    @ArchTest
    static final ArchRule sin_imports_de_otros_servicios =
            noClasses().that().resideInAPackage("com.fintech.disbursement..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fintech.creditportfolio..", "com.fintech.wallet..",
                            "com.fintech.payments..", "com.fintech.stp..", "com.fintech.origination..",
                            // BK-10: tesorería decide por dónde sale el dinero, y este servicio se
                            // lo pregunta por su puerto ACL. Que la respuesta llegue por HTTP y no
                            // por un import es lo que permite cambiar quién decide sin recompilar
                            // el orquestador.
                            "com.fintech.banking..")
                    .because("la única dependencia permitida es 'shared' (@ApplicationModule)");

    /** El vocabulario de crédito sólo puede aparecer en los adaptadores de entrada (ACL). */
    @ArchTest
    static final ArchRule credito_solo_en_el_acl =
            classes().that().haveSimpleNameContaining("CreditAccount")
                    .or().haveSimpleNameContaining("Disposition")
                    .or().haveSimpleNameContaining("Wallet")
                    .should().resideInAPackage("..infrastructure.adapter.in.messaging..")
                    .because("borrar esos archivos debe bastar para extraer el servicio (§7.5)");

    /** El dominio es Java puro: se prueba sin levantar un contexto de Spring. */
    @ArchTest
    static final ArchRule el_dominio_no_depende_de_spring =
            noClasses().that().resideInAPackage("com.fintech.disbursement.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    /** Hexagonal, en serio: el dominio no conoce a la aplicación ni a la infraestructura. */
    @ArchTest
    static final ArchRule el_dominio_no_mira_hacia_afuera =
            noClasses().that().resideInAPackage("com.fintech.disbursement.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fintech.disbursement.application..",
                            "com.fintech.disbursement.infrastructure..");

    /** Y la aplicación no conoce a la infraestructura: sólo sus propios puertos. */
    @ArchTest
    static final ArchRule la_aplicacion_no_conoce_la_infraestructura =
            noClasses().that().resideInAPackage("com.fintech.disbursement.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fintech.disbursement.infrastructure..")
                    .because("si la aplicación importa un adaptador, la arquitectura hexagonal es decorativa");

    /** Ningún adaptador de salida puede saltarse el puerto y hablar con otro adaptador de salida. */
    @ArchTest
    static final ArchRule los_adaptadores_no_se_llaman_entre_si =
            noClasses().that().resideInAPackage("..infrastructure.adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure.adapter.out..")
                    .because("entrada y salida se comunican por el núcleo, no por atajo");
}
