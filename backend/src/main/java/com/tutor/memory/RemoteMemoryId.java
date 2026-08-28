package com.tutor.memory;

import java.util.regex.Pattern;

/** Mem0 远端记忆标识的统一边界校验，避免本地存储与同步协议规则漂移。 */
public final class RemoteMemoryId {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private RemoteMemoryId() {
    }

    public static boolean isValid(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    public static void requireValid(String value) {
        if (!isValid(value)) throw new IllegalArgumentException("invalid remote memory id");
    }
}
