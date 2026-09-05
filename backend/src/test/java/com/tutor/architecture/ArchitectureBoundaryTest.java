package com.tutor.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 防止模块化单体在日常修改中重新形成反向耦合。 */
public class ArchitectureBoundaryTest {
    @Test
    void allBoundaryRulesAreExecuted() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.tutor");
        retrievalDoesNotDependOnChat.check(classes);
        knowledgeDoesNotDependOnChat.check(classes);
        apiDoesNotDependOnLlmImplementations.check(classes);
        planApplicationServiceDoesNotOwnPersistenceOrScheduling.check(classes);
        careerGapApplicationServiceDoesNotOwnJobSql.check(classes);
        knowledgeAdminServiceDoesNotOwnDocumentSql.check(classes);
        notificationControllerDoesNotOwnPersistence.check(classes);
        controllersDoNotOwnPersistence.check(classes);
        profileApplicationServiceDoesNotOwnPersistence.check(classes);
        skillAlignServiceDoesNotOwnPersistence.check(classes);
        interviewReportServiceDoesNotOwnWorkerInfrastructure.check(classes);
        jsonGatewayContractDoesNotDependOnProviderSdk.check(classes);
        llmPortsDoNotDependOnProviderSdk.check(classes);
        memoryDoesNotDependOnChatApi.check(classes);
        memoryConsentServiceDoesNotOwnPersistence.check(classes);
        repositoryBeansAreNotFinal.check(classes);
    }

    public static final ArchRule retrievalDoesNotDependOnChat = noClasses().that().resideInAnyPackage("com.tutor.retrieval..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..");
    public static final ArchRule knowledgeDoesNotDependOnChat = noClasses().that().resideInAnyPackage("com.tutor.knowledge..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..");
    public static final ArchRule apiDoesNotDependOnLlmImplementations = noClasses().that().haveSimpleNameEndingWith("Controller").should().dependOnClassesThat().resideInAnyPackage("com.tutor.llm..");
    public static final ArchRule planApplicationServiceDoesNotOwnPersistenceOrScheduling = noClasses().that().haveSimpleName("PlanService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "org.springframework.scheduling..");
    public static final ArchRule careerGapApplicationServiceDoesNotOwnJobSql = noClasses().that().haveSimpleName("CareerGapService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule knowledgeAdminServiceDoesNotOwnDocumentSql = noClasses().that().haveSimpleName("KnowledgeDocumentAdminService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule notificationControllerDoesNotOwnPersistence = noClasses().that().haveSimpleName("NotificationController").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule controllersDoNotOwnPersistence = noClasses().that().haveSimpleNameEndingWith("Controller").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule skillAlignServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("SkillAlignService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule profileApplicationServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("ProfileService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule interviewReportServiceDoesNotOwnWorkerInfrastructure = noClasses().that().haveSimpleName("InterviewReportService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.scheduling..", "java.util.concurrent..", "jakarta.annotation..");
    public static final ArchRule jsonGatewayContractDoesNotDependOnProviderSdk = noClasses().that().haveSimpleName("JsonGenerationGateway").should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");
    public static final ArchRule llmPortsDoNotDependOnProviderSdk = noClasses().that().areInterfaces().and().resideInAnyPackage("com.tutor.llm..").should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");
    public static final ArchRule memoryDoesNotDependOnChatApi = noClasses().that().resideInAnyPackage("com.tutor.conversation.memory..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..", "com.tutor.conversation.chat.api..");
    public static final ArchRule memoryConsentServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("MemoryConsentService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    // Spring Boot 的 PersistenceExceptionTranslationPostProcessor 会为每个 @Repository bean 生成
    // CGLIB 代理; final 的 Repository 会让应用启动直接失败(且单测不加载完整上下文, 只有真实启动才暴露)。
    public static final ArchRule repositoryBeansAreNotFinal = noClasses()
            .that().areAnnotatedWith("org.springframework.stereotype.Repository")
            .should().haveModifier(JavaModifier.FINAL);
}
