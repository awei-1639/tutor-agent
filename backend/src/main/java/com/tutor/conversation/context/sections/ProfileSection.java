package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 区2: 用户画像快照。只注入 confidence≥0.5 的字段, 超限按置信度降序裁剪 (3.1)。 */
@Component
@Order(2)
public class ProfileSection implements ContextSection {

    @Override public String name() { return "profile"; }
    @Override public int budget() { return 500; }

    @Override
    @SuppressWarnings("unchecked")
    public String render(TurnContextView ctx, TokenBudget budget) {
        Map<String, Object> p = ctx.profile();
        if (p == null || p.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## 用户画像（置信度≥0.5）\n");

        appendScalar(sb, p, "target_position", "目标岗位");
        appendScalar(sb, p, "location", "期望城市");
        appendScalar(sb, p, "experience_years", "工作年限");
        appendScalar(sb, p, "education", "学历");
        appendScalar(sb, p, "daily_hours", "每日可学习小时");

        Object skillsObj = p.get("skills");
        if (skillsObj instanceof List<?> skills && !skills.isEmpty()) {
            List<String> parts = skills.stream()
                    .map(s -> (Map<String, Object>) s)
                    .filter(s -> conf(s.get("confidence")) >= 0.5)
                    .sorted((a, b) -> Double.compare(conf(b.get("confidence")), conf(a.get("confidence"))))
                    .map(s -> s.get("name") + "(" + trim(conf(s.get("confidence"))) + ")")
                    .toList();
            if (!parts.isEmpty()) sb.append("技能: ").append(String.join(", ", parts)).append('\n');
        }
        Object formats = p.get("preferred_format");
        if (formats instanceof List<?> f && !f.isEmpty()) {
            sb.append("偏好学习形式: ").append(String.join(",", f.stream().map(Object::toString).toList())).append('\n');
        }
        String out = sb.toString();
        return out.lines().count() <= 1 ? "" : budget.truncate(out, budget());
    }

    @SuppressWarnings("unchecked")
    private void appendScalar(StringBuilder sb, Map<String, Object> p, String key, String label) {
        Object o = p.get(key);
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> field = (Map<String, Object>) m;
            if (conf(field.get("confidence")) >= 0.5 && field.get("value") != null) {
                sb.append(label).append(": ").append(field.get("value"))
                        .append("(").append(trim(conf(field.get("confidence")))).append(")\n");
            }
        }
    }

    private static double conf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String trim(double d) {
        return d >= 0.995 ? "1.0" : String.format("%.1f", d);
    }
}
