package com.tutor.memory.policy;

import com.tutor.resume.PiiMasker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 生成文本成为持久记忆前的确定性准入边界。 */
@Component
public class MemoryAdmissionPolicy {
    private static final int MAX_SUMMARY_CHARS = 600;
    private static final List<String> INSTRUCTION_MARKERS = List.of(
            "ignore previous", "ignore all", "system prompt", "developer message",
            "忽略之前", "忽略上述", "系统提示", "开发者消息", "你现在是");

    public boolean acceptsEpisode(String summary, List<String> topics, List<String> openItems) {
        if (summary == null || summary.isBlank() || summary.length() > MAX_SUMMARY_CHARS) return false;
        String normalized = summary.toLowerCase(Locale.ROOT);
        if (INSTRUCTION_MARKERS.stream().anyMatch(normalized::contains)) return false;
        // 不持久化仍含确定性脱敏器识别出的 PII 的候选项；远端脱敏属于纵深防御，而非准入条件。
        if (!PiiMasker.mask(summary).mapping().isEmpty()) return false;
        return safeList(topics, 8, 80) && safeList(openItems, 8, 160);
    }

    public boolean acceptsSummary(String summary) {
        if (summary == null || summary.isBlank() || summary.length() > 600) return false;
        String normalized = summary.toLowerCase(Locale.ROOT);
        return INSTRUCTION_MARKERS.stream().noneMatch(normalized::contains)
                && PiiMasker.mask(summary).mapping().isEmpty();
    }

    private boolean safeList(List<String> values, int maxItems, int maxChars) {
        if (values == null) return true;
        if (values.size() > maxItems) return false;
        return values.stream().allMatch(value -> value != null && !value.isBlank()
                && value.length() <= maxChars && INSTRUCTION_MARKERS.stream()
                .noneMatch(marker -> value.toLowerCase(Locale.ROOT).contains(marker))
                // 添加前缀可避免将每个两到四字主题（如“完成项目”）都识别为人名，
                // 同时仍能捕获值中嵌入的强标识符。
                && PiiMasker.mask("文本: " + value).mapping().isEmpty());
    }
}
