package com.fintech.stp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DC-2 — prueba ejecutable de que este servicio se puede vender por separado.
 *
 * <p>Va en su propia clase, no dentro del test de {@code disbursement-service}: {@code @AnalyzeClasses}
 * sólo importa el paquete que se le indica, así que una regla sobre {@code com.fintech.stp} escrita
 * allá evaluaría sobre un conjunto vacío — y ArchUnit falla por conjunto vacío desde la 0.23.
 */
@AnalyzeClasses(packages = "com.fintech.stp", importOptions = ImportOption.DoNotIncludeTests.class)
class StpDecouplingTest {

    @ArchTest
    static final ArchRule no_conoce_el_dominio_de_credito_ni_de_desembolso =
            noClasses().that().resideInAPackage("com.fintech.stp..")
                    .should().haveSimpleNameContaining("Disbursement")
                    .orShould().haveSimpleNameContaining("CreditAccount")
                    .orShould().haveSimpleNameContaining("Disposition")
                    .orShould().haveSimpleNameContaining("Wallet")
                    .because("stp-service es un conector de proveedor: debe poder extraerse a otro "
                            + "repositorio sin borrar un solo archivo");

    @ArchTest
    static final ArchRule sin_imports_de_otros_servicios =
            noClasses().that().resideInAPackage("com.fintech.stp..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fintech.disbursement..", "com.fintech.creditportfolio..",
                            "com.fintech.wallet..", "com.fintech.payments..", "com.fintech.origination..",
                            // BK-07: la cuenta ordenante llega en el mensaje. Este conector no
                            // consulta a tesorería ni la conoce — sólo usa lo que le mandaron.
                            "com.fintech.banking..")
                    .because("la única dependencia permitida es 'shared' (@ApplicationModule)");

    @ArchTest
    static final ArchRule el_dominio_no_depende_de_spring =
            noClasses().that().resideInAPackage("com.fintech.stp.domain.signing..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .because("el módulo de firma es Java puro: tiene que poder probarse sin contexto");
}
