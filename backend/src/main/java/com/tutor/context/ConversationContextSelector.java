package com.tutor.context;

import com.tutor.memory.local.ConversationStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在固定最近窗口内做轻量相关性筛选。
 * 最近两条消息始终保留，其余位置优先保留与当前问题有词汇/短语重叠的消息。
 * 不调用 LLM，避免为组装上下文增加新的不确定性和成本。
 */
public final class ConversationContextSelector {
    private static final Pattern LATIN_OR_NUMBER = Pattern.compile("[a-z0-9][a-z0-9_+#.-]{1,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "((?:这|那)(?:个|些|样|里|边|怎么|如何)?|它|其|上述|前面|后面|刚才|上面|下面|上一轮|之前|继续|再来)");
    private static final int ALWAYS_KEEP_RECENT = 2;
    private static final int MAX_QUERY_TERMS = 64;
    private static final int MAX_CONTEXT_ANCHOR_CHARS = 600;

    /** 常见虚词/指代词不参与相关性计算，避免长问题被普通词稀释。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "我们", "你们", "他们", "这个", "那个", "这些", "那些", "现在", "刚刚", "前面", "后面",
            "怎么", "如何", "为什么", "什么", "哪个", "哪些", "可以", "能够", "希望", "想要",
            "进行", "一下", "一个", "一些", "有关", "以及", "然后", "但是", "因为", "所以", "如果",
            "是否", "还是", "只是", "比较", "非常", "目前", "一般", "来说", "的话", "其中",
            "请你", "帮我", "给我", "告诉我", "讲清", "一下子", "东西", "内容", "问题");

    /** 项目领域词和常见技术短语，优先于普通中文 n-gram。 */
    private static final Set<String> DOMAIN_TERMS = Set.of(
            "后端", "前端", "学习计划", "工作计划", "简历", "岗位", "用户画像", "会话历史", "上下文",
            "路由", "置信度", "执行计划", "检索", "澄清", "专家", "节点", "算法", "模型", "提示词",
            "关键词", "相关性", "保序回归", "上下文工程", "身份确认", "并发控制", "token控制",
            "java", "spring boot", "springboot", "python", "javascript", "typescript", "react", "api",
            "jwt", "llm", "embedding", "neo4j");

    private static final Set<String> ACTION_TERMS = Set.of(
            "制定", "分析", "解释", "实现", "优化", "学习", "比较", "提取", "构建", "开发", "排查", "校准");

    private static final Set<String> STATE_TERMS = Set.of(
            "不会", "会", "掌握", "了解", "正在", "已经", "缺少", "需要", "优先", "必须");

    private ConversationContextSelector() {
    }

    public static List<ConversationStore.Msg> select(List<ConversationStore.Msg> history,
                                                      String question, int maxMessages) {
        if (history == null || history.isEmpty() || maxMessages <= 0) return List.of();
        if (history.size() <= maxMessages) return List.copyOf(history);

        int recentCount = Math.min(ALWAYS_KEEP_RECENT, Math.min(maxMessages, history.size()));
        int recentStart = history.size() - recentCount;
        Map<String, Double> queryTerms = terms(question, MAX_QUERY_TERMS);
        List<Scored> candidates = new ArrayList<>();
        for (int index = 0; index < recentStart; index++) {
            ConversationStore.Msg message = history.get(index);
            double overlap = overlap(queryTerms, terms(message.content, MAX_QUERY_TERMS));
            double score = overlap == 0D ? 0D
                    : overlap + (index / (double) Math.max(1, history.size())) * 0.05D;
            candidates.add(new Scored(index, message, score));
        }

        int slots = maxMessages - recentCount;
        List<Scored> selected = candidates.stream()
                .filter(candidate -> candidate.score() > 0D)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Scored::index, Comparator.reverseOrder()))
                .limit(slots)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (selected.size() < slots) {
            Set<Integer> chosen = selected.stream().map(Scored::index).collect(java.util.stream.Collectors.toSet());
            candidates.stream()
                    .sorted(Comparator.comparingInt(Scored::index).reversed())
                    .filter(candidate -> !chosen.contains(candidate.index()))
                    .limit(slots - selected.size())
                    .forEach(selected::add);
        }
        for (int index = recentStart; index < history.size(); index++) {
            selected.add(new Scored(index, history.get(index), Double.POSITIVE_INFINITY));
        }
        return selected.stream().sorted(Comparator.comparingInt(Scored::index))
                .map(Scored::message).toList();
    }

    /**
     * 为路由器准备最小上下文：默认只传最近两条用户消息。
     * 当本轮包含指代词或是很短的追问时，额外带上最近一条助手回复，作为主题锚点。
     */
    public static List<String> routerContext(List<ConversationStore.Msg> history, String question) {
        if (history == null || history.isEmpty()) return List.of();
        List<String> recentUsers = new ArrayList<>();
        for (int index = history.size() - 1; index >= 0 && recentUsers.size() < 2; index--) {
            ConversationStore.Msg message = history.get(index);
            if ("user".equals(message.role) && message.content != null && !message.content.isBlank()) {
                recentUsers.add(0, message.content);
            }
        }
        if (!needsContextAnchor(question)) return List.copyOf(recentUsers);

        String anchor = latestAssistantReply(history);
        if (anchor != null) {
            List<String> result = new ArrayList<>(recentUsers);
            result.add("[相关上一轮回复] " + anchor);
            return List.copyOf(result);
        }
        return List.copyOf(recentUsers);
    }

    /**
     * @deprecated 查询上下文改写统一由 {@link ContextualQueryRewriter} 负责。
     * 保留该方法是为了兼容已有调用方和测试。
     */
    @Deprecated
    public static String contextualize(String question, List<ConversationStore.Msg> history) {
        if (question == null || question.isBlank() || !needsContextAnchor(question)) return question;
        String anchor = latestAssistantReply(history);
        if (anchor == null) return question;
        return "相关上下文主题：" + truncate(anchor, MAX_CONTEXT_ANCHOR_CHARS)
                + "\n当前问题：" + question;
    }

    public static boolean needsContextAnchor(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = question.trim();
        if (CONTEXT_REFERENCE.matcher(normalized).find()) return true;
        if (normalized.codePointCount(0, normalized.length()) > 8) return false;
        // 短问题只有在几乎没有主题词时才补锚点；“分析后端岗位”虽短，但主题已经明确。
        return terms(normalized, 8).isEmpty();
    }

    private static String latestAssistantReply(List<ConversationStore.Msg> history) {
        if (history == null) return null;
        for (int index = history.size() - 1; index >= 0; index--) {
            ConversationStore.Msg message = history.get(index);
            if ("assistant".equals(message.role) && message.content != null && !message.content.isBlank()) {
                return message.content;
            }
        }
        return null;
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…";
    }

    private static double overlap(Map<String, Double> queryTerms, Map<String, Double> messageTerms) {
        if (queryTerms.isEmpty() || messageTerms.isEmpty()) return 0D;
        double totalWeight = queryTerms.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0D) return 0D;
        double matchedWeight = queryTerms.entrySet().stream()
                .filter(entry -> messageTerms.containsKey(entry.getKey()))
                .mapToDouble(Map.Entry::getValue)
                .sum();
        return matchedWeight / totalWeight;
    }

    /**
     * 提取“名词/技术词为主，动作和状态词为辅”的加权关键词，并限制数量。
     * 中文没有天然空格，故以领域词和 2～4 字 n-gram 作为无 NLP 依赖的回退。
     */
    private static Map<String, Double> terms(String text, int limit) {
        if (text == null || text.isBlank() || limit <= 0) return Map.of();
        String normalized = text.toLowerCase(Locale.ROOT);
        Map<String, Double> candidates = new LinkedHashMap<>();

        // 先加入领域词/技术短语，确保长问题中真正有价值的词不会被普通 n-gram 挤掉。
        for (String domainTerm : DOMAIN_TERMS) {
            if (normalized.contains(domainTerm)) {
                candidates.merge(domainTerm, 3.0D, Math::max);
            }
        }

        Matcher latin = LATIN_OR_NUMBER.matcher(normalized);
        while (latin.find()) {
            String term = latin.group();
            if (!STOP_WORDS.contains(term)) candidates.merge(term, 3.0D, Math::max);
        }

        Matcher han = HAN_RUN.matcher(normalized);
        while (han.find()) {
            String run = han.group();
            // 长 n-gram 更像名词短语，先生成 4 字再生成 3/2 字。
            for (int size = 4; size >= 2; size--) {
                for (int start = 0; start + size <= run.length(); start++) {
                    String term = run.substring(start, start + size);
                    if (STOP_WORDS.contains(term)) continue;
                    double weight = switch (size) {
                        case 4 -> 2.4D;
                        case 3 -> 1.7D;
                        default -> 1.0D;
                    };
                    if (DOMAIN_TERMS.contains(term)) weight = 3.0D;
                    else if (ACTION_TERMS.contains(term)) weight = 1.5D;
                    else if (STATE_TERMS.contains(term)) weight = 1.4D;
                    candidates.merge(term, weight, Math::max);
                }
            }
        }

        return candidates.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().length(), Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private record Scored(int index, ConversationStore.Msg message, double score) {
    }
}
