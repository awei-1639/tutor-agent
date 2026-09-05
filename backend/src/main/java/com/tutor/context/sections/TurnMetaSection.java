package com.tutor.context.sections;

import com.tutor.context.ContextSection;
import com.tutor.context.TokenBudget;
import com.tutor.context.TurnContextView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 区7: 回合元信息（当前日期）。物理输出排在所有分区之后：
 * 日期每日变化，放在末尾使其之前的前缀（规则/画像/记忆/证据）保持逐字节稳定，
 * 命中供应商前缀缓存；且紧邻对话开始，模型对末尾信息的关注度最高。
 */
@Component
@Order(7)
public class TurnMetaSection implements ContextSection {

    @Override public String name() { return "meta"; }
    @Override public int budget() { return 64; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        return "\n当前日期: " + LocalDate.now() + "（涉及周期、截止时间的回答以此为准）\n";
    }
}
