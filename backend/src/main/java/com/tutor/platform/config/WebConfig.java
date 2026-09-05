package com.tutor.platform.config;

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

    public WebConfig(AuthInterceptor auth, CsrfInterceptor csrf) {
        this.auth = auth;
        this.csrf = csrf;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/**");
        registry.addInterceptor(csrf).addPathPatterns("/**");
    }
}
