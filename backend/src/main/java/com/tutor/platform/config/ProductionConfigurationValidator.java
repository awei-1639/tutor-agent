package com.tutor.platform.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * 生产环境的 fail-fast 配置防线。仅校验配置完整性，不在启动期间探测第三方网络，
 * 从而避免把临时网络抖动误判成错误配置。
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements SmartInitializingSingleton {
    private final LlmProperties llm;
    private final OssProperties oss;
    private final Mem0Properties mem0;
    private final ClamAvProperties clamAv;
    private final AliyunOcrProperties aliyunOcr;

    @Value("${security.resume-enc-key:}")
    private String conversationEncryptionKey;

    @Value("${security.resume-enc-key-id:v1}")
    private String currentEncryptionKeyId;

    @Value("${security.resume-enc-previous-key:}")
    private String previousEncryptionKey;

    @Value("${security.resume-enc-previous-key-id:}")
    private String previousEncryptionKeyId;

    /** 编辑配置注释曾宣称「生产 profile 会强制关闭」，此处把它变成真正的失败条件。 */
    @Value("${tutor.auth.dev-login-enabled:false}")
    private boolean devLoginEnabled;

    /** 同一类：/internal 是评估工具入口，没有任何业务理由出现在生产实例上。 */
    @Value("${tutor.internal.enabled:false}")
    private boolean internalEndpointsEnabled;

    public ProductionConfigurationValidator(LlmProperties llm, OssProperties oss, Mem0Properties mem0, ClamAvProperties clamAv, AliyunOcrProperties aliyunOcr) {
        this.llm = llm;
        this.oss = oss;
        this.mem0 = mem0;
        this.clamAv = clamAv;
        this.aliyunOcr = aliyunOcr;
    }

    @Override
    public void afterSingletonsInstantiated() {
        llm.requireProductionConfiguration();
        if (oss.enabled()) oss.requireConfigured();
        mem0.requireEnabledConfiguration();
        clamAv.requireConfigured();
        aliyunOcr.requireConfigured();
        if (conversationEncryptionKey != null && conversationEncryptionKey.isBlank()) {
            throw new IllegalStateException("RESUME_ENC_KEY 未配置：生产环境拒绝保存会话原文");
        }
        if (previousEncryptionKey != null && !previousEncryptionKey.isBlank()
                && (previousEncryptionKeyId == null || previousEncryptionKeyId.isBlank())) {
            throw new IllegalStateException("配置了 RESUME_ENC_PREVIOUS_KEY 但缺少 RESUME_ENC_PREVIOUS_KEY_ID");
        }
        if (previousEncryptionKey != null && !previousEncryptionKey.isBlank()
                && previousEncryptionKeyId.equals(currentEncryptionKeyId)) {
            throw new IllegalStateException("新旧加密密钥版本标识不能相同");
        }
        // dev-login 用固定口令 devpass 自动建号，等价于公网可达的注册后门。
        // 这条以前只写在配置注释里，没有任何代码强制——注释不是防线。
        if (devLoginEnabled) {
            throw new IllegalStateException("生产环境禁止开启 DEV_LOGIN_ENABLED");
        }
        // /internal 跳过认证与 CSRF，且能指定 userId 读写记忆；生产实例启用它没有正当用途。
        if (internalEndpointsEnabled) {
            throw new IllegalStateException("生产环境禁止启用 /internal 评估端点 (INTERNAL_ENDPOINTS_ENABLED)");
        }
    }
}
