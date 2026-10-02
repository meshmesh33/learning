package com.example.pim.bootstrap;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;

/**
 * Keeps the hexagon honest: dependencies only point inwards and adapters never call each other.
 * (bootstrap is outside every layer; wiring everything together is its job.)
 */
@AnalyzeClasses(packages = "com.example.pim", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule hexagonalLayers = onionArchitecture()
            .domainModels("com.example.pim.domain..")
            .applicationServices("com.example.pim.application..")
            .adapter("persistence", "com.example.pim.adapter.persistence..")
            .adapter("outbox", "com.example.pim.adapter.outbox..")
            .adapter("web", "com.example.pim.adapter.web..")
            .withOptionalLayers(true)
            .ignoreDependency(resideInAPackage("com.example.pim.bootstrap.."), alwaysTrue());

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses()
            .that().resideInAPackage("com.example.pim.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages("com.example.pim.domain..", "java..");

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses()
            .that().resideInAPackage("com.example.pim.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.apache.kafka..", "java.sql..");
}
