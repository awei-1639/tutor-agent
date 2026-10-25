package com.tutor.platform.config;

import com.tutor.identity.admin.AdminGateInterceptor;
import com.tutor.identity.auth.AuthInterceptor;
import com.tutor.identity.auth.CsrfInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 (Phase 4 V4 4.x): 注册 JWT 拦截器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    private final CsrfInterceptor csrf;
    private final AdminGateInterceptor adminGate;

    public WebConfig(AuthInterceptor auth, CsrfInterceptor csrf, AdminGateInterceptor adminGate) {
        this.auth = auth;
        this.csrf = csrf;
        this.adminGate = adminGate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/**");
        registry.addInterceptor(csrf).addPathPatterns("/**");
        // 顺序即依赖: adminGate 读取 AuthInterceptor 写入的 AuthContext, 必须排在其后
        registry.addInterceptor(adminGate).addPathPatterns("/admin/**");
    }
}
