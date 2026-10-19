package com.bank.bpo.payments;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.camunda.client.annotation.JobWorker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The ports-and-adapters rules of the worker: the ports know nothing of the engine or of HTTP. */
class HexagonalArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bank.bpo.payments");
    }

    @Test
    void portsDependOnNeitherTheEngineNorHttpNorAdapters() {
        noClasses().that().resideInAPackage("..payments.port..")
            .should().dependOnClassesThat().resideInAnyPackage("io.camunda..", "java.net..", "..payments.adapter..",
                "..payments.handler..", "org.springframework..", "tools.jackson..")
            .check(production);
    }

    @Test
    void handlersTalkToPortsNotToAdapters() {
        noClasses().that().resideInAPackage("..payments.handler..")
            .should().dependOnClassesThat().resideInAnyPackage("..payments.adapter..", "java.net..")
            .check(production);
    }

    @Test
    void adaptersDoNotKnowTheEngineOrTheHandlers() {
        noClasses().that().resideInAPackage("..payments.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("io.camunda..", "..payments.handler..")
            .check(production);
    }

    @Test
    void everyAdapterImplementsAPort() {
        classes().that().resideInAPackage("..payments.adapter..")
            .should().implement(com.tngtech.archunit.base.DescribedPredicate.describe("a port interface",
                (i) -> i.getPackageName().endsWith("payments.port")))
            .check(production);
    }

    @Test
    void onlyHandlersDeclareJobWorkers() {
        noClasses().that().resideOutsideOfPackage("..payments.handler..")
            .should().dependOnClassesThat().areAssignableTo(JobWorker.class)
            .check(production);
    }
}
