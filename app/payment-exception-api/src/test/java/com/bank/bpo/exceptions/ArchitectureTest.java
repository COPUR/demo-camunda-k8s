package com.bank.bpo.exceptions;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The hexagon as fitness functions: dependencies point inwards (config, adapters, application, domain), the domain
 * is plain Java, each technology stays in its one adapter, and the two sub-domains do not know each other.
 */
class ArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bank.bpo.exceptions");
    }

    @Test
    void theDomainIsPlainJava() {
        classes().that().resideInAPackage("..exceptions.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..exceptions.domain..")
            .check(production);
    }

    @Test
    void theApplicationKnowsOnlyTheDomainAndItsPorts() {
        classes().that().resideInAPackage("..exceptions.application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..exceptions.domain..", "..exceptions.application..")
            .check(production);
    }

    @Test
    void theSubDomainsDoNotDependOnEachOther() {
        noClasses().that().resideInAPackage("..domain.casemanagement..")
            .should().dependOnClassesThat().resideInAPackage("..domain.catalog..").check(production);
        noClasses().that().resideInAPackage("..domain.catalog..")
            .should().dependOnClassesThat().resideInAPackage("..domain.casemanagement..").check(production);
    }

    @Test
    void onlyTheRestAdapterSpeaksTheProtobufContract() {
        noClasses().that().resideOutsideOfPackages("..exceptions.adapter.in.rest..", "..exceptions.contract..")
            .should().dependOnClassesThat().resideInAPackage("..exceptions.contract..")
            .check(production);
    }

    @Test
    void onlyTheEventsAdapterSpeaksAvroAndKafka() {
        noClasses().that().resideOutsideOfPackages("..exceptions.adapter.out.events..", "..exceptions.config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.avro..", "org.apache.kafka..")
            .check(production);
    }

    @Test
    void onlyTheCamundaAdapterTalksToTheEngine() {
        noClasses().that().resideOutsideOfPackages("..exceptions.adapter.out.camunda..", "..exceptions.config..")
            .should().dependOnClassesThat().resideInAPackage("io.camunda..")
            .check(production);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        slices().matching("com.bank.bpo.exceptions.adapter.(*).(*)..").should().notDependOnEachOther().check(production);
    }

    @Test
    void nothingDependsOnTheCompositionRoot() {
        noClasses().that().resideOutsideOfPackage("..exceptions.config..")
            .and().doNotHaveSimpleName("PaymentExceptionApiApplication")
            .should().dependOnClassesThat().resideInAPackage("..exceptions.config..")
            .check(production);
    }

    @Test
    void thePackagesAreFreeOfCycles() {
        slices().matching("com.bank.bpo.exceptions.(**)").should().beFreeOfCycles().check(production);
    }
}
