package com.tutor.agent.tool;

import com.tutor.platform.config.CodedException;

/** 工具执行失败。继承 {@link CodedException} 后由全局异常处理统一映射状态码。 */
public class ToolExecutionException extends CodedException {

    public ToolExecutionException(String code, String message) {
        super(code, message);
    }

    public ToolExecutionException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
