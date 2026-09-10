package com.tutor.platform.config;

/**
 * 带机器可读错误码的业务异常基类, 供全局异常处理映射 HTTP 状态码。
 *
 * <p>业务异常继承该类后, 全局 {@code ApiExceptionHandler} 无需依赖具体异常类型
 * (避免 platform 反向依赖业务域), 只依据 {@link #code()} 做状态码翻译。
 *
 * <p>之所以是抽象类而不是接口: Spring 的 {@code @ExceptionHandler} 只接受
 * {@code Class<? extends Throwable>}, 接口无法作为字节码层面的分派依据。
 */
public abstract class CodedException extends RuntimeException {

    private final String code;

    protected CodedException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected CodedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** 稳定错误码, 例如 {@code INVALID_INPUT} / {@code TIMEOUT}。 */
    public String code() {
        return code;
    }
}
