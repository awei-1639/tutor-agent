package com.tutor.platform.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明端点的 operation 埋点标签, 让异常的 failure 计数由全局异常处理统一打点。
 *
 * <p>标签值显式写在注解里, 而不是从方法名推导: 重命名方法不会静默改变
 * Prometheus 标签, 从而不会破坏既有看板与告警。success / 业务自定义结果
 * 仍由 Controller 自己打, advice 只补 failure 这一类。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackedOperation {

    /** Micrometer counter 名称。 */
    String counter();

    /** operation 标签值, 例如 open / answer / retest / cancel。 */
    String operation();
}
