package com.tutor.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/** token 估算 (jtokkit cl100k; 对中文与deepseek分词有偏差, 作预算控制足够) */
@Component
public class TokenBudget {
    private final Encoding enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        return enc.countTokens(text);
    }

    /** 按 token 上限截断 (保头部) */
    public String truncate(String text, int maxTokens) {
        if (count(text) <= maxTokens) return text;
        // 二分找截断点, 避免逐字符重编码
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (count(text.substring(0, mid)) <= maxTokens) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + "…";
    }
}
