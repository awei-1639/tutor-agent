package com.tutor.identity.admin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式管理员门禁 (诊断报告 2026-09-10 §4.3, 修复清单 #9)。
 *
 * <p>标注在 {@code @RestController} 类或方法上, 由 {@link AdminGateInterceptor} 在请求进入前统一校验:
 * 未登录 401、非管理员 403。此前鉴权散落在各 Service 方法开头的手工 {@code requireAdmin()} 调用上,
 * 新增端点忘记一行就是裸奔; 注解 + ArchUnit 规则让它成为结构约束。
 * Service 层的 {@code requireAdmin()} 保留作为纵深防御 (同时取 operatorId 供审计)。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
