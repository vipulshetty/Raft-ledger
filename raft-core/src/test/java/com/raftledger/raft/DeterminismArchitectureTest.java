package com.raftledger.raft;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DeterminismArchitectureTest {
    @Test
    void raftCoreHasNoNondeterministicOrIoDependencies() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.raftledger.raft");
        noClasses().that().resideInAnyPackage("com.raftledger.raft..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..", "java.net..", "java.time..",
                        "java.util.concurrent..", "java.util.random..")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.raft..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "java.lang.Thread")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.raft..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Random")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.raft..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "java.util.concurrent.ThreadLocalRandom")
                .check(classes);
        noClasses().that().resideInAnyPackage("com.raftledger.raft..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.UUID")
                .check(classes);
    }
}
