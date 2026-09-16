package com.tutor.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
        platformDoesNotDependOnConversation.check(classes);
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
        applicationServicesDoNotOwnJdbc.check(classes);
        controllersDoNotDependOnStores.check(classes);
        crossDomainStoreAccessIsFrozen.check(classes);
    }

    public static final ArchRule retrievalDoesNotDependOnChat = noClasses().that().resideInAnyPackage("com.tutor.knowledge.retrieval..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..");
    public static final ArchRule knowledgeDoesNotDependOnChat = noClasses().that().resideInAnyPackage("com.tutor.knowledge.document..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..");
    // platform 是最底层：任何反向依赖 conversation 的类都会让"拆服务"和"复用底座"同时失效。
    public static final ArchRule platformDoesNotDependOnConversation = noClasses().that().resideInAnyPackage("com.tutor.platform..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation..");
    public static final ArchRule apiDoesNotDependOnLlmImplementations = noClasses().that().haveSimpleNameEndingWith("Controller").should().dependOnClassesThat().resideInAnyPackage("com.tutor.platform.llm..");
    public static final ArchRule planApplicationServiceDoesNotOwnPersistenceOrScheduling = noClasses().that().haveSimpleName("PlanService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "org.springframework.scheduling..");
    public static final ArchRule careerGapApplicationServiceDoesNotOwnJobSql = noClasses().that().haveSimpleName("CareerGapService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule knowledgeAdminServiceDoesNotOwnDocumentSql = noClasses().that().haveSimpleName("KnowledgeDocumentAdminService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule notificationControllerDoesNotOwnPersistence = noClasses().that().haveSimpleName("NotificationController").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule controllersDoNotOwnPersistence = noClasses().that().haveSimpleNameEndingWith("Controller").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule skillAlignServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("SkillAlignService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule profileApplicationServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("ProfileService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    public static final ArchRule interviewReportServiceDoesNotOwnWorkerInfrastructure = noClasses().that().haveSimpleName("InterviewReportService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.scheduling..", "java.util.concurrent..", "jakarta.annotation..");
    public static final ArchRule jsonGatewayContractDoesNotDependOnProviderSdk = noClasses().that().haveSimpleName("JsonGenerationGateway").should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");
    public static final ArchRule llmPortsDoNotDependOnProviderSdk = noClasses().that().areInterfaces().and().resideInAnyPackage("com.tutor.platform.llm..").should().dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");
    public static final ArchRule memoryDoesNotDependOnChatApi = noClasses().that().resideInAnyPackage("com.tutor.conversation.memory..").should().dependOnClassesThat().resideInAnyPackage("com.tutor.conversation.chat..", "com.tutor.conversation.chat.api..");
    public static final ArchRule memoryConsentServiceDoesNotOwnPersistence = noClasses().that().haveSimpleName("MemoryConsentService").should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");
    // Spring Boot 的 PersistenceExceptionTranslationPostProcessor 会为每个 @Repository bean 生成
    // CGLIB 代理; final 的 Repository 会让应用启动直接失败(且单测不加载完整上下文, 只有真实启动才暴露)。
    public static final ArchRule repositoryBeansAreNotFinal = noClasses()
            .that().areAnnotatedWith("org.springframework.stereotype.Repository")
            .should().haveModifier(JavaModifier.FINAL);

    // ---- 2026-09-16 review 后新增: 冻结存量、阻断增量 ----
    // 存量违规类在 KNOWN_* 豁免集里, 只允许减少不允许增加: 新类落进来立刻变红。

    private static final Set<String> KNOWN_JDBC_USERS = Set.of(
            "AuthService", "RagEvalService", "InterviewReportService",
            "InterviewScoreEvalService", "InterviewScoreAnnotationService",
            "KnowledgeDocumentService", "PushService", "MemorySyncWorker",
            "KnowledgeIngestionWorker",
            // 2026-09-16 校准: 以下为存量直接持有 JdbcTemplate 的 Service/Gateway, 冻结不允许新增
            "InterviewTurnService", "ChatTurnService",
            "EpisodeRetentionService", "FactRetentionService",
            "KnowledgeChunkPublicationService", "KnowledgeEmbeddingStagingService",
            "KnowledgeIngestionService",
            "HealthReadinessService", "BudgetPressureService",
            "LlmGateway", "InternalMemorySeedService", "ResumeService");
    private static final Set<String> KNOWN_CONTROLLER_STORE_USERS = Set.of(
            "InternalController", "MemoryController", "ConversationController",
            "NotificationController");
    private static final Set<String> KNOWN_CROSS_DOMAIN_STORE_USERS = Set.of(
            "PlanContextService", "InternalMemorySeedService", "ResumeService");

    private static boolean isKnownLegacy(String simpleName, Set<String> legacy) {
        return legacy.contains(simpleName);
    }

    /** Service/Worker/Gateway 是应用编排层, SQL 只能下沉到 @Repository Store。 */
    public static final ArchRule applicationServicesDoNotOwnJdbc = noClasses()
            .that().haveSimpleNameEndingWith("Service")
            .or().haveSimpleNameEndingWith("Worker")
            .or().haveSimpleNameEndingWith("Gateway")
            .and(new DescribedPredicate<JavaClass>("not a known legacy jdbc user") {
                @Override
                public boolean test(JavaClass c) {
                    return !isKnownLegacy(c.getSimpleName(), KNOWN_JDBC_USERS);
                }
            })
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..");

    /** Controller 通过应用服务访问数据, 不直接持有 Store。 */
    public static final ArchRule controllersDoNotDependOnStores = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .and(new DescribedPredicate<JavaClass>("not a known legacy controller->store user") {
                @Override
                public boolean test(JavaClass c) {
                    return !isKnownLegacy(c.getSimpleName(), KNOWN_CONTROLLER_STORE_USERS);
                }
            })
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Store")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Outbox");

    /** 跨域直达他人 Store 是拆服务的第一堵墙; 存量冻结, 新增即红。 */
    public static final ArchRule crossDomainStoreAccessIsFrozen = noClasses()
            .that().resideInAnyPackage("com.tutor.coaching..", "com.tutor.evaluation..", "com.tutor.identity..")
            .and(new DescribedPredicate<JavaClass>("not a known legacy cross-domain store user") {
                @Override
                public boolean test(JavaClass c) {
                    return !isKnownLegacy(c.getSimpleName(), KNOWN_CROSS_DOMAIN_STORE_USERS);
                }
            })
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.tutor.conversation.memory.local..", "com.tutor.knowledge.retrieval.vector..");
}
