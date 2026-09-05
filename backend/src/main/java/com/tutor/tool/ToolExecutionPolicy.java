package com.tutor.tool;

import com.tutor.contract.SideEffect;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.Set;

/** Pure tool admission policy: agent permission, side-effect safeguards, and input validation. */
final class ToolExecutionPolicy {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    void validate(ToolRegistration registration, Object input, ToolExecutionContext context) {
        if (!registration.allowedAgents().contains(context.agent())) {
            throw new ToolExecutionException("FORBIDDEN", "当前 agent 无权调用工具");
        }
        SideEffect level = registration.spec().level();
        if (level != SideEffect.L0 && (context.idempotencyKey() == null || context.idempotencyKey().isBlank())) {
            throw new ToolExecutionException("IDEMPOTENCY_REQUIRED", "有副作用的工具必须提供幂等键");
        }
        if (level == SideEffect.L2 && !context.confirmed()) {
            throw new ToolExecutionException("CONFIRMATION_REQUIRED", "外部动作需要用户确认");
        }
        if (input == null || !registration.spec().inputSchema().isInstance(input)) {
            throw new ToolExecutionException("INVALID_INPUT", "工具参数类型不符合契约");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(input);
        if (!violations.isEmpty()) throw new ToolExecutionException("INVALID_INPUT", "工具参数校验失败");
    }
}
