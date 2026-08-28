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
        if (text == null || text.isEmpty()) return 0;
        return enc.countTokens(text);
    }

    /** 返回不添加省略号时能够容纳的最长前缀。 */
    public String prefix(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (count(text) <= maxTokens) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (count(text.substring(0, mid)) <= maxTokens) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo);
    }

    /** 返回不添加省略号时能够容纳的最长后缀。 */
    public String suffix(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (count(text) <= maxTokens) return text;
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (count(text.substring(mid)) <= maxTokens) hi = mid;
            else lo = mid + 1;
        }
        return text.substring(lo);
    }

    /**
     * 保留超长文本的首尾两端。该方法适用于检索输入，因为末尾常包含用户的最终约束。
     */
    public String headTail(String text, int maxTokens, double headRatio) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (count(text) <= maxTokens) return text;
        String marker = "\n…\n";
        int available = maxTokens - count(marker);
        if (available <= 1) return prefix(text, maxTokens);
        double ratio = Double.isFinite(headRatio) ? Math.max(0.1D, Math.min(0.9D, headRatio)) : 0.6D;
        int headTokens = Math.max(1, (int) Math.floor(available * ratio));
        int tailTokens = Math.max(1, available - headTokens);
        String result = headTailParts(text, marker, headTokens, tailTokens);
        for (int attempt = 0; count(result) > maxTokens && (headTokens > 1 || tailTokens > 1); attempt++) {
            if (tailTokens >= headTokens && tailTokens > 1) tailTokens--;
            else if (headTokens > 1) headTokens--;
            else tailTokens--;
            result = headTailParts(text, marker, headTokens, tailTokens);
            if (attempt > maxTokens * 2) break;
        }
        return count(result) <= maxTokens ? result : prefix(text, maxTokens);
    }

    private String headTailParts(String text, String marker, int headTokens, int tailTokens) {
        return prefix(text, headTokens) + marker + suffix(text, tailTokens);
    }

    /** 按 token 上限截断 (保头部) */
    public String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (count(text) <= maxTokens) return text;
        // 二分找截断点, 避免逐字符重编码
        int lo = 0, hi = text.length();
        String suffix = "…";
        boolean suffixFits = count(suffix) <= maxTokens;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String candidate = text.substring(0, mid) + (suffixFits ? suffix : "");
            if (count(candidate) <= maxTokens) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + (suffixFits ? suffix : "");
    }
}
