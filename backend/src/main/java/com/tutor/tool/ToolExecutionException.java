package com.tutor.tool;

public class ToolExecutionException extends RuntimeException {
    private final String code;

    public ToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ToolExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
}
