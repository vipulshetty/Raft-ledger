package com.raftledger.ledger;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DeterminismArchitectureTest {
    @Test
    void ledgerCoreHasNoNondeterministicOrIoDependencies() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.raftledger.ledger");
        noClasses().that().resideInAnyPackage("com.raftledger.ledger..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..", "java.net..", "java.time..",
                        "java.util.concurrent..", "java.util.random..")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.ledger..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "java.lang.Thread")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.ledger..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.ledger..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "java.util.concurrent.ThreadLocalRandom")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.ledger..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.UUID")
                .check(classes);
    }
}
