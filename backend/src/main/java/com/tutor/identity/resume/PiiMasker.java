package com.tutor.identity.resume;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 脱敏 — 纯函数可单测 (V3 ch7 / 实现设计 8.3)。
 * 必须是本地确定性规则: LLM调用发生在脱敏之后, 不能用LLM找PII (鸡生蛋问题)。
 * 覆盖: 手机号 / 邮箱 / 身份证号 / 银行卡号 / 微信号 / 标注式地址和姓名 / 姓名启发式(首行2-4汉字)。
 * 正文中段的无标签姓名仍不做自动识别，避免把普通中文短语误伤为姓名。
 */
public final class PiiMasker {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,19}(?!\\d)");
    private static final Pattern WECHAT = Pattern.compile("(?i)(?:(?:微信号|wechat|wxid)\\s*[:：]?\\s*)([a-z][-_a-z0-9]{5,19})");
    private static final Pattern LABELED_NAME = Pattern.compile("(?i)(姓名|联系人|收件人|name)\\s*[:：]\\s*([\\u4e00-\\u9fa5]{2,4}|[A-Za-z][A-Za-z .'-]{1,50})");
    private static final Pattern LABELED_ADDRESS = Pattern.compile("(?i)(地址|住址|收货地址|开户地址|address)\\s*[:：]\\s*([^\\n,，;；]{4,120})");
    private static final Pattern NAME_LINE = Pattern.compile("^[\\u4e00-\\u9fa5]{2,4}$");
    /** 常见文档标题, 不是姓名 */
    private static final Pattern TITLE_WORDS = Pattern.compile("简历|履历|求职|应聘");

    private PiiMasker() {}

    public record MaskResult(String masked, Map<String, String> mapping) {}

    public static MaskResult mask(String text) {
        if (text == null || text.isBlank()) return new MaskResult(text == null ? "" : text, Map.of());
        Map<String, String> mapping = new LinkedHashMap<>();
        String out = replaceAll(text, ID_CARD, "IDCARD", mapping);   // 先长后短, 防手机号吃掉身份证片段
        out = replaceAll(out, PHONE, "PHONE", mapping);
        out = replaceAll(out, EMAIL, "EMAIL", mapping);
        out = replaceAll(out, BANK_CARD, "BANKCARD", mapping);
        out = replaceLabeled(out, WECHAT, "WECHAT", mapping);
        out = replaceLabeled(out, LABELED_NAME, "NAME", mapping);
        out = replaceLabeled(out, LABELED_ADDRESS, "ADDRESS", mapping);

        // 姓名启发式: 全文最前面的非空行若为2-4个纯汉字, 视为姓名
        String[] lines = out.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.isEmpty()) continue;
            if (NAME_LINE.matcher(t).matches() && !TITLE_WORDS.matcher(t).find()) {
                String ph = "[NAME_1]";
                mapping.put(ph, t);
                lines[i] = lines[i].replace(t, ph);
                out = String.join("\n", lines);
                // 正文中再出现同名也一并替换 (自称/落款)
                out = out.replace(t, ph).replace(ph.replace(t, ph), ph);
            }
            break; // 只看首个非空行
        }
        return new MaskResult(out, mapping);
    }

    private static String replaceLabeled(String text, Pattern pattern, String tag, Map<String, String> mapping) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group(matcher.groupCount());
            String placeholder = "[" + tag + "_" + (mapping.size() + 1) + "]";
            mapping.put(placeholder, original);
            String replacement = matcher.group().substring(0, matcher.group().length() - original.length()) + placeholder;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceAll(String text, Pattern p, String tag, Map<String, String> mapping) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find()) {
            String ph = "[" + tag + "_" + (++n) + "]";
            mapping.put(ph, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(ph));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
