package com.tutor.conversation.memory.policy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 记忆写入前的确定性重要性门控：窗口内没有任何显著性信号时跳过本轮 Episode 抽取，
 * 省掉一次摘要 + 一次事实抽取 + 一次 embedding 的 LLM 成本。
 * 门槛刻意宽松：宁可放行闲聊窗口让准入策略兜底，也不漏掉措辞特殊的真实陈述；
 * 被跳过的窗口不推进水位线，后续消息累积后仍会重试。
 */
@Component
public class MemoryImportanceGate {

    static final List<Pattern> SALIENT_PATTERNS = List.of(
            // 目标与打算
            Pattern.compile("(我想|我打算|我准备|我计划|我的目标|我希望能|我要|我的方向)"),
            // 进行中的学习/求职动作
            Pattern.compile("(我在学|我在准备|我正在学|我正在准备|我想学|我想做|我在做|我在找|我在投)"),
            // 偏好
            Pattern.compile("(我喜欢|我不喜欢|我偏好|我更愿意|我倾向于|我习惯|我不习惯|别给我|不要给我)"),
            // 时间与约束
            Pattern.compile("(每周|每天|时间有限|没时间|只有.{0,6}(小时|天)|在职|应届|工作日|下班|周末)"),
            // 背景与经验
            Pattern.compile("(我是|我毕业于|我有.{0,8}经验|我的经验|我的专业|做过.{0,10}(项目|开发|实习))"),
            // 对助手的纠正（高价值：修正后的信息才代表用户当前状态）
            Pattern.compile("(你理解错了|你弄错了|不对，|不是这样|我说的是|重新说|纠正一下|其实我)"),
            // 明确的规划/简历请求
            Pattern.compile("(帮我制定|帮我规划|帮我安排|给我制定|给我规划|帮我看看简历|帮我改简历|帮我优化简历)"),
            // 掌握度陈述
            Pattern.compile("(我会|我不会|我不太会|我不熟|不太熟|不熟悉|我熟悉|我掌握|我了解|不太了解|没学过|刚学|学过)")
    );

    /** 显著性信号之外，超长用户陈述也值得沉淀。 */
    static final int SALIENT_MESSAGE_CHARS = 120;

    public boolean hasSalientSignal(String maskedUserWindow) {
        if (maskedUserWindow == null || maskedUserWindow.isBlank()) return false;
        for (Pattern pattern : SALIENT_PATTERNS) {
            if (pattern.matcher(maskedUserWindow).find()) return true;
        }
        // 逐消息长度兜底：长消息往往有实质内容，避免模式漏杀。
        for (String line : maskedUserWindow.split("\n")) {
            if (line.strip().length() >= SALIENT_MESSAGE_CHARS) return true;
        }
        return false;
    }
}
