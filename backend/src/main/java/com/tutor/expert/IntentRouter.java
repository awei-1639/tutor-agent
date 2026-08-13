package com.tutor.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 意图路由 (V3 3.2): 最小context (枚举定义+最近2轮), 轻量调用。
 * 降级矩阵: provider/预算失败 → CHAT (避免故障时扇出三路专家放大成本);
 * 返回内容格式错误但调用成功 → CHAT (避免无效输出触发三路专家扇出)。
 */
@Component
public class IntentRouter {
    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final String SYS = """
            你是意图分类器。将用户请求分类为以下之一, 输出JSON {"intent":"..."}:
            - resume: 简历内容优化、简历与岗位匹配度、投递建议
            - interview: 面试题、笔试题、面试准备与模拟
            - planning: 学习路径、学习计划、技能提升规划
            - mixed: 同时涉及上述两类及以上的综合请求
            - chat: 与学习求职相关的一般咨询问答 (概念解释、岗位信息查询等)
            - out_of_scope: 与AI学习/求职完全无关的请求
            只输出JSON。
            """;

    private final LlmGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntentRouter(LlmGateway gateway) {
        this.gateway = gateway;
    }

    public Intent route(String question, List<String> recentUserMessages, String traceId) {
        try {
            String context = recentUserMessages.isEmpty() ? ""
                    : "此前用户消息: " + String.join(" / ", recentUserMessages) + "\n";
            String json = gateway.chatJson(Purpose.ROUTER, List.of(
                    SystemMessage.from(SYS),
                    UserMessage.from(context + "当前请求: " + question)), traceId);
            return parseIntent(json, mapper);
        } catch (Exception e) {
            log.warn("router不可用, 降级CHAT trace={} type={}", traceId, e.getClass().getSimpleName());
            return Intent.CHAT;
        }
    }

    /** 纯函数可单测: 解析失败/未知值 → CHAT，避免无效输出放大成本。 */
    static Intent parseIntent(String json, ObjectMapper mapper) {
        try {
            String v = mapper.readTree(json).path("intent").asText("");
            return Intent.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Intent.CHAT;
        }
    }
}
