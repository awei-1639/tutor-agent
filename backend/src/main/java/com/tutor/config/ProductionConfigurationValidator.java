package com.tutor.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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

    public ProductionConfigurationValidator(LlmProperties llm, OssProperties oss, Mem0Properties mem0) {
        this.llm = llm;
        this.oss = oss;
        this.mem0 = mem0;
    }

    @Override
    public void afterSingletonsInstantiated() {
        llm.requireProductionConfiguration();
        if (oss.enabled()) oss.requireConfigured();
        mem0.requireEnabledConfiguration();
    }
}
