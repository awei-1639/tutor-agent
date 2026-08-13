package com.tutor.memory.policy;

import com.tutor.resume.PiiMasker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Deterministic boundary before generated text becomes durable memory. */
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
        // Do not persist a candidate that still contains PII detected by the
        // deterministic masker.  Remote masking is defence in depth, not admission.
        if (!PiiMasker.mask(summary).mapping().isEmpty()) return false;
        return safeList(topics, 8, 80) && safeList(openItems, 8, 160);
    }

    private boolean safeList(List<String> values, int maxItems, int maxChars) {
        if (values == null) return true;
        if (values.size() > maxItems) return false;
        return values.stream().allMatch(value -> value != null && !value.isBlank()
                && value.length() <= maxChars && INSTRUCTION_MARKERS.stream()
                .noneMatch(marker -> value.toLowerCase(Locale.ROOT).contains(marker)));
    }
}
