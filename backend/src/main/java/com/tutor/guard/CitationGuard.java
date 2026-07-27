package com.tutor.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Evidence;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-RAG 引用护栏 (Phase 3 V4 3.4): 运行时校验回答中每条结论是否被引用支撑。
 * 不支撑的结论标记 unsupported, 调用方可选删除或重生成。
 * 铁律: 护栏错误不阻断流式输出, 仅记录供观测。
 */
@Component
public class CitationGuard {
    private static final Logger log = LoggerFactory.getLogger(CitationGuard.class);
    private static final Pattern CITE = Pattern.compile("\\[S(\\d+)]");

    private static final String SYS = """
            你是引用忠实度护栏。检查回答中的事实陈述是否被对应 [S#] 引用的 evidence 支撑。
            规则:
            - 每条事实/数字/定义性陈述必须有 [S#] 引用, 且 evidence 文本能支撑该陈述
            - 寒暄/总结/承上启下等无事实陈述不需要引用, 视为 supported
            输出 JSON {"claims":[{"text":"...","sid":"S1","verdict":"supported|unsupported"}], "summary":"N条/M条被支撑"}
            """;

    private final LlmGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public CitationGuard(LlmGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 校验回答中每条引用 [S#] 是否合理
     * @return 校验结果, 失败返回空列表 (静默降级)
     */
    public GuardResult guard(String answer, List<Evidence> evidences, String traceId) {
        // 提取回答中实际使用的 [S#]
        Matcher m = CITE.matcher(answer);
        List<Integer> usedIndices = new ArrayList<>();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1)) - 1;
            if (idx >= 0 && idx < evidences.size()) usedIndices.add(idx);
        }
        if (usedIndices.isEmpty()) {
            return new GuardResult(0, 0, List.of(), 1.0); // 无引用 = 100% "未支撑" (按护栏视角)
        }

        StringBuilder evidenceList = new StringBuilder();
        for (int idx : usedIndices) {
            Evidence e = evidences.get(idx);
            evidenceList.append("[S").append(idx + 1).append("] ")
                    .append(e.nodeId()).append(": ")
                    .append(e.chunkText(), 0, Math.min(e.chunkText().length(), 300))
                    .append("\n");
        }
        String prompt = "回答:\n" + answer + "\n\n引用的证据:\n" + evidenceList;

        try {
            String json = gateway.chatJson(Purpose.JUDGE, List.of(
                    SystemMessage.from(SYS), UserMessage.from(prompt)), traceId);
            var node = mapper.readTree(json);
            int supported = 0, unsupported = 0;
            List<String> issues = new ArrayList<>();
            for (var c : node.path("claims")) {
                String verdict = c.path("verdict").asText("");
                if ("unsupported".equals(verdict)) {
                    unsupported++;
                    issues.add(c.path("text").asText("").substring(0, Math.min(50, c.path("text").asText("").length())));
                } else {
                    supported++;
                }
            }
            int total = supported + unsupported;
            double rate = total > 0 ? (double) supported / total : 1.0;
            log.info("护栏 trace={} supported={}/{} rate={}", traceId, supported, total, rate);
            return new GuardResult(supported, unsupported, issues, rate);
        } catch (Exception e) {
            log.warn("护栏失败 (静默降级) trace={}: {}", traceId, e.getMessage());
            return new GuardResult(0, usedIndices.size(), List.of(), 0.0);
        }
    }

    public record GuardResult(int supported, int unsupported, List<String> issues, double supportRate) {}
}