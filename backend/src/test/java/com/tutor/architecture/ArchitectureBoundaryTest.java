package com.tutor.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 防止模块化单体在日常修改中重新形成反向耦合。 */
@AnalyzeClasses(packages = "com.tutor", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {
    @ArchTest
    static final ArchRule retrievalDoesNotDependOnChat =
            noClasses().that().resideInAnyPackage("com.tutor.retrieval..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.tutor.chat..");

    @ArchTest
    static final ArchRule knowledgeDoesNotDependOnChat =
            noClasses().that().resideInAnyPackage("com.tutor.knowledge..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.tutor.chat..");

    @ArchTest
    static final ArchRule apiDoesNotDependOnLlmImplementations =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().resideInAnyPackage("com.tutor.llm..");
}
